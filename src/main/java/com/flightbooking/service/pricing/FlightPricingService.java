package com.flightbooking.service.pricing;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.repository.FlightSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Composes the {@link PricingService} pricing chain with the "how
 * many seats are already booked?" query so callers don't have to
 * reproduce the {@code (count → derive available → quote)}
 * boilerplate every time they need a live price.
 *
 * <p>{@link PricingService} stays a pure computation over
 * {@code (flight, availableSeats, totalSeats)} — the DB coupling
 * lives here, in one place, and gets exercised by every seat-count-
 * sensitive caller (reserve, flight details, search).</p>
 *
 * <p>Two shapes because their DB access patterns are fundamentally
 * different:</p>
 * <ul>
 *   <li>{@link #quoteFor(Flight)} — single flight, one COUNT query
 *       via {@link FlightSeatRepository#countByFlight_Id}. Used by
 *       reserve (only one seat is priced) and flight-details (one
 *       flight per request).</li>
 *   <li>{@link #quoteForAll(Collection)} — many flights, one batched
 *       COUNT via {@link FlightSeatRepository#countBookedByFlightIds}.
 *       Used by search where 10-100 unique flights can appear across
 *       the returned itineraries; the naive per-flight loop would be
 *       a textbook N+1.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class FlightPricingService {

    private final FlightSeatRepository flightSeatRepository;
    private final PricingService pricingService;

    /**
     * Live quote for {@code flight}. Runs a COUNT to find how many
     * seats are booked, then delegates to the pricing chain.
     *
     * <p>If the caller already has the booked-seat count in hand
     * (e.g. from a LEFT JOIN on {@code flight_seats}), call
     * {@link #quoteFor(Flight, long)} instead to skip the COUNT.</p>
     */
    public PriceQuote quoteFor(Flight flight) {
        long booked = flightSeatRepository.countByFlight_Id(flight.getId());
        return quoteFor(flight, booked);
    }

    /**
     * Live quote for {@code flight} using a caller-supplied booked
     * count. Callers own freshness — the value must be observed
     * inside the same {@code @Transactional} boundary as this call
     * so the price reflects a consistent snapshot.
     *
     * @param bookedSeats  number of {@code flight_seats} rows the
     *                     caller has already observed for this flight
     */
    public PriceQuote quoteFor(Flight flight, long bookedSeats) {
        int total = flight.getFlightModel().getTotalSeats();
        return pricingService.quote(flight, total - bookedSeats, total);
    }

    /**
     * Live quotes for every flight in {@code flights}, keyed by
     * {@code flightId}. Duplicates in the input are deduped — a
     * flight that appears in ten itineraries is priced exactly once
     * — and the entire batch shares a single COUNT query regardless
     * of input size. Returns an empty map (never {@code null}) for
     * an empty input.
     */
    public Map<Long, PriceQuote> quoteForAll(Collection<Flight> flights) {
        if (flights == null || flights.isEmpty()) {
            return Map.of();
        }
        Map<Long, Flight> flightById = new HashMap<>();
        for (Flight f : flights) {
            flightById.put(f.getId(), f);
        }

        Map<Long, Long> bookedByFlight = new HashMap<>();
        flightSeatRepository.countBookedByFlightIds(flightById.keySet())
                .forEach(row -> bookedByFlight.put(row.getFlightId(), row.getSeatCount()));

        Map<Long, PriceQuote> quotes = new HashMap<>(flightById.size());
        for (Map.Entry<Long, Flight> e : flightById.entrySet()) {
            Flight f = e.getValue();
            int total = f.getFlightModel().getTotalSeats();
            long avail = total - bookedByFlight.getOrDefault(e.getKey(), 0L);
            quotes.put(e.getKey(), pricingService.quote(f, avail, total));
        }
        return quotes;
    }
}
