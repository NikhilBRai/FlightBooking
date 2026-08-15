package com.flightbooking.service;

import com.flightbooking.api.dto.FlightDetailsDto;
import com.flightbooking.api.dto.FlightSummaryDto;
import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.api.dto.SeatDto;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.enums.SeatStatus;
import com.flightbooking.domain.enums.SortBy;
import com.flightbooking.exception.ResourceNotFoundException;
import com.flightbooking.repository.FlightRepository;
import com.flightbooking.repository.SeatOccupancyRow;
import com.flightbooking.repository.SeatRepository;
import com.flightbooking.service.pricing.FlightPricingService;
import com.flightbooking.service.pricing.PriceQuote;
import com.flightbooking.service.reservation.SeatLockService;
import com.flightbooking.service.search.FlightSearchStrategy;
import com.flightbooking.service.search.ItinerarySortService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlightService {

    private final FlightRepository flightRepository;
    private final SeatRepository seatRepository;
    private final SeatLockService seatLockService;
    private final FlightPricingService flightPricingService;
    private final ItinerarySortService itinerarySortService;
    /**
     * Actual graph-search implementation. Chosen at boot via
     * {@code app.search.strategy} — currently {@code batched} (default,
     * two DB round-trips per search) or {@code recursive} (one round-trip
     * per recursion node, kept as a fallback for benchmarking).
     */
    private final FlightSearchStrategy searchStrategy;

    @Value("${app.search.max-stops-cap:3}")
    private int maxStopsCap;

    /**
     * Search for itineraries and price every unique flight across all
     * of them in one batched pass. Three concerns are separated:
     *
     * <ol>
     *   <li><b>Graph search</b> — delegated to
     *       {@link FlightSearchStrategy#findPaths} which enumerates
     *       every completed {@code List<Flight>} path. This method has
     *       no idea whether the strategy is running a naive recursive
     *       DB walk or a single-fetch pool expansion.</li>
     *   <li><b>Pricing</b> — every unique flight across the returned
     *       paths is quoted exactly once (batched booked-count query,
     *       then one pricing chain per flight). A leg that appears in
     *       ten itineraries is not priced ten times.</li>
     *   <li><b>Sorting</b> — deferred to {@link ItinerarySortService},
     *       which picks a sorter from the {@link SortBy} enum.</li>
     * </ol>
     *
     * <p>The user's {@code maxStops} is clamped here (before hitting
     * the strategy) so every strategy can trust the value it receives —
     * no strategy re-implements the API-level input validation.</p>
     */
    @Transactional(readOnly = true)
    public List<ItineraryDto> searchFlights(String source,
                                            String destination,
                                            LocalDate date,
                                            Integer maxStops,
                                            SortBy sortBy) {
        int requested = (maxStops == null) ? maxStopsCap : Math.max(0, maxStops);
        int effectiveMaxStops = Math.min(requested, maxStopsCap);

        List<List<Flight>> completedPaths = searchStrategy.findPaths(
                source, destination, date, effectiveMaxStops);
        if (completedPaths.isEmpty()) {
            return List.of();
        }

        // Batched pricing across the union of every unique flight in
        // every path — dedupes inside FlightPricingService so a flight
        // appearing in ten itineraries is priced exactly once.
        Map<Long, Flight> flightById = new HashMap<>();
        for (List<Flight> path : completedPaths) {
            for (Flight f : path) {
                flightById.put(f.getId(), f);
            }
        }
        Map<Long, PriceQuote> quoteByFlightId =
                flightPricingService.quoteForAll(flightById.values());

        Map<Long, FlightSummaryDto> summaryByFlightId = new HashMap<>(flightById.size());
        for (Map.Entry<Long, Flight> e : flightById.entrySet()) {
            summaryByFlightId.put(e.getKey(),
                    toSummary(e.getValue(), quoteByFlightId.get(e.getKey())));
        }

        List<ItineraryDto> results = new ArrayList<>(completedPaths.size());
        for (List<Flight> path : completedPaths) {
            List<FlightSummaryDto> segments = path.stream()
                    .map(f -> summaryByFlightId.get(f.getId()))
                    .toList();
            results.add(buildItinerary(segments));
        }

        return itinerarySortService.sort(sortBy, results);
    }

    /**
     * Returns everything a seat-selection UI needs for one flight —
     * flight + aircraft + per-seat status + live price — via:
     *
     * <ol>
     *   <li><b>Q1</b> — {@link FlightRepository#findByIdWithFlightModel}
     *       loads the flight together with its aircraft model
     *       (needed for {@code getTotalSeats()} in pricing and
     *       {@code getMake()} in the DTO).</li>
     *   <li><b>Q2</b> — {@link SeatRepository#findSeatOccupancy}
     *       returns every seat in the model with a nullable pointer
     *       to its {@code flight_seats} row on this flight; a
     *       non-null pointer means booked.</li>
     *   <li><b>Redis</b> — {@link SeatLockService#getLockedSeatIds}
     *       overlays the transient LOCKED state on top of the
     *       not-yet-booked candidates. Not a SQL query.</li>
     * </ol>
     *
     * <p>The booked count from Q2 is forwarded to
     * {@link FlightPricingService#quoteFor(Flight, long)}, so pricing
     * doesn't need to re-count.</p>
     */
    @Transactional(readOnly = true)
    public FlightDetailsDto getFlightDetails(Long flightId) {
        Flight flight = flightRepository.findByIdWithFlightModel(flightId)
                .orElseThrow(() -> new ResourceNotFoundException("Flight not found: " + flightId));

        List<SeatOccupancyRow> layout = seatRepository.findSeatOccupancy(
                flightId, flight.getFlightModel().getId());

        Set<Long> bookedSeatIds = layout.stream()
                .filter(SeatOccupancyRow::isBooked)
                .map(SeatOccupancyRow::seatId)
                .collect(Collectors.toSet());

        // Redis is only asked about the not-yet-booked candidates —
        // no point checking a lock on a seat we already know is
        // confirmed.
        List<Long> candidateSeatIds = layout.stream()
                .filter(row -> !row.isBooked())
                .map(SeatOccupancyRow::seatId)
                .toList();
        Set<Long> lockedSeatIds = seatLockService.getLockedSeatIds(flightId, candidateSeatIds);

        PriceQuote quote = flightPricingService.quoteFor(flight, bookedSeatIds.size());

        List<SeatDto> seats = layout.stream()
                .map(row -> toSeatDto(row, bookedSeatIds, lockedSeatIds))
                .toList();

        return FlightDetailsDto.builder()
                .flight(toSummary(flight, quote))
                .seats(seats)
                .build();
    }

    // ---- Helpers -------------------------------------------------------

    private ItineraryDto buildItinerary(List<FlightSummaryDto> segments) {
        FlightSummaryDto first = segments.get(0);
        FlightSummaryDto last = segments.get(segments.size() - 1);
        long totalDuration = Duration.between(first.startTime(), last.endTime()).toMinutes();
        // Sum of ground time at every intermediate airport.
        long layover = 0L;
        for (int i = 0; i < segments.size() - 1; i++) {
            layover += Duration.between(
                    segments.get(i).endTime(),
                    segments.get(i + 1).startTime()).toMinutes();
        }
        BigDecimal totalPrice = segments.stream()
                .map(FlightSummaryDto::estimatedPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return ItineraryDto.builder()
                .segments(segments)
                .stops(segments.size() - 1)
                .startTime(first.startTime())
                .endTime(last.endTime())
                .totalDurationMinutes(totalDuration)
                .layoverMinutes(layover)
                .totalPrice(totalPrice)
                .build();
    }

    private FlightSummaryDto toSummary(Flight f, PriceQuote quote) {
        return FlightSummaryDto.builder()
                .flightId(f.getId())
                .source(f.getSource())
                .destination(f.getDestination())
                .startTime(f.getStartTime())
                .endTime(f.getEndTime())
                .durationMinutes(f.getTimeTaken().toMinutes())
                .baseFare(f.getCost())
                .estimatedPrice(quote.finalPrice())
                .priceBreakdown(quote.breakdown())
                .aircraft(f.getFlightModel().getMake())
                .fullyBooked(f.isFullyBooked())
                .build();
    }

    private SeatDto toSeatDto(SeatOccupancyRow row, Set<Long> bookedSeatIds, Set<Long> lockedSeatIds) {
        SeatStatus status;
        if (bookedSeatIds.contains(row.seatId())) {
            status = SeatStatus.BOOKED;
        } else if (lockedSeatIds.contains(row.seatId())) {
            status = SeatStatus.LOCKED;
        } else {
            status = SeatStatus.AVAILABLE;
        }
        return SeatDto.builder()
                .seatId(row.seatId())
                .seatNumber(row.seatNumber())
                .status(status)
                .build();
    }
}
