package com.flightbooking.service;

import com.flightbooking.api.dto.FlightDetailsDto;
import com.flightbooking.api.dto.FlightSummaryDto;
import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.api.dto.PriceBreakdownEntry;
import com.flightbooking.api.dto.SeatDto;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FlightService delegates graph search to a strategy and pricing to
 * FlightPricingService — so we mock both and verify:
 *   - stops clamp is enforced (input beyond cap gets clamped to cap)
 *   - each path becomes an itinerary with segments correctly stitched
 *   - the returned itineraries are handed to the sort service (i.e. we
 *     don't accidentally sort locally then re-sort — that would break
 *     the pluggable-sorter contract)
 *   - getFlightDetails composes booked (from flight_seats), locked
 *     (from Redis lock service, excluding booked seats), and available
 *     into the seat map correctly
 */
@ExtendWith(MockitoExtension.class)
class FlightServiceTest {

    @Mock FlightRepository flightRepository;
    @Mock SeatRepository seatRepository;
    @Mock SeatLockService seatLockService;
    @Mock FlightPricingService flightPricingService;
    @Mock ItinerarySortService itinerarySortService;
    @Mock FlightSearchStrategy searchStrategy;

    @InjectMocks FlightService svc;

    private FlightModel boeing;
    private Flight blrBom;
    private Flight blrHyd;
    private Flight hydBom;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(svc, "maxStopsCap", 3);
        boeing = FlightModel.builder().id(1L).make("Boeing").totalSeats(6).build();
        Instant t0 = Instant.parse("2030-01-01T00:00:00Z");
        blrBom = Flight.builder().id(1L).flightModel(boeing).source("BLR").destination("BOM")
                .startTime(t0).endTime(t0.plus(Duration.ofHours(2))).cost(new BigDecimal("3200")).build();
        blrHyd = Flight.builder().id(2L).flightModel(boeing).source("BLR").destination("HYD")
                .startTime(t0).endTime(t0.plus(Duration.ofMinutes(90))).cost(new BigDecimal("2200")).build();
        hydBom = Flight.builder().id(3L).flightModel(boeing).source("HYD").destination("BOM")
                .startTime(t0.plus(Duration.ofHours(3))).endTime(t0.plus(Duration.ofHours(4).plusMinutes(30)))
                .cost(new BigDecimal("1900")).build();
    }

    private static PriceQuote quote(BigDecimal price) {
        return new PriceQuote(price, List.of(new PriceBreakdownEntry("s", price, "n")));
    }

    // ---- searchFlights -------------------------------------------------

    @Test
    void searchFlights_emptyPathsShortCircuitsToEmpty() {
        when(searchStrategy.findPaths(any(), any(), any(), anyInt())).thenReturn(List.of());
        assertThat(svc.searchFlights("BLR", "BOM", LocalDate.parse("2030-01-01"), 1, SortBy.CHEAPEST)).isEmpty();
    }

    @Test
    @DisplayName("maxStops is clamped to configured cap")
    void searchFlights_clampsMaxStops() {
        when(searchStrategy.findPaths(eq("BLR"), eq("BOM"), any(), eq(3))).thenReturn(List.of());
        svc.searchFlights("BLR", "BOM", LocalDate.parse("2030-01-01"), 99, SortBy.CHEAPEST);
    }

    @Test
    @DisplayName("null maxStops defaults to cap")
    void searchFlights_nullMaxStopsDefaultsToCap() {
        when(searchStrategy.findPaths(eq("BLR"), eq("BOM"), any(), eq(3))).thenReturn(List.of());
        svc.searchFlights("BLR", "BOM", LocalDate.parse("2030-01-01"), null, SortBy.CHEAPEST);
    }

    @Test
    @DisplayName("negative maxStops is clamped to zero (direct flights only)")
    void searchFlights_negativeMaxStopsClampsToZero() {
        when(searchStrategy.findPaths(eq("BLR"), eq("BOM"), any(), eq(0))).thenReturn(List.of());
        svc.searchFlights("BLR", "BOM", LocalDate.parse("2030-01-01"), -5, SortBy.CHEAPEST);
    }

    @Test
    @DisplayName("prices are batched via FlightPricingService.quoteForAll — never per-flight from FlightService")
    void searchFlights_pricesViaBatchedApi() {
        when(searchStrategy.findPaths(any(), any(), any(), anyInt()))
                .thenReturn(List.of(List.of(blrBom), List.of(blrHyd, hydBom)));
        when(flightPricingService.quoteForAll(any())).thenReturn(Map.of(
                1L, quote(new BigDecimal("3000")),
                2L, quote(new BigDecimal("2000")),
                3L, quote(new BigDecimal("1800"))));
        when(itinerarySortService.sort(eq(SortBy.CHEAPEST), any())).thenAnswer(inv -> inv.getArgument(1));

        List<ItineraryDto> out = svc.searchFlights("BLR", "BOM",
                LocalDate.parse("2030-01-01"), 1, SortBy.CHEAPEST);

        assertThat(out).hasSize(2);
        ItineraryDto direct = out.stream().filter(it -> it.stops() == 0).findFirst().orElseThrow();
        ItineraryDto oneStop = out.stream().filter(it -> it.stops() == 1).findFirst().orElseThrow();

        assertThat(direct.totalPrice()).isEqualByComparingTo("3000");
        assertThat(direct.segments()).extracting(FlightSummaryDto::flightId).containsExactly(1L);

        assertThat(oneStop.totalPrice()).isEqualByComparingTo("3800"); // 2000 + 1800
        assertThat(oneStop.segments()).extracting(FlightSummaryDto::flightId).containsExactly(2L, 3L);
        assertThat(oneStop.layoverMinutes()).isEqualTo(90); // HYD ground time
    }

    @Test
    @DisplayName("sort is delegated to the sort service — FlightService doesn't order itself")
    void searchFlights_sortIsDelegated() {
        when(searchStrategy.findPaths(any(), any(), any(), anyInt())).thenReturn(List.of(List.of(blrBom)));
        when(flightPricingService.quoteForAll(any())).thenReturn(Map.of(1L, quote(new BigDecimal("100"))));
        List<ItineraryDto> ordered = List.of();
        when(itinerarySortService.sort(eq(SortBy.FASTEST), any())).thenReturn(ordered);

        assertThat(svc.searchFlights("BLR", "BOM", LocalDate.parse("2030-01-01"), 0, SortBy.FASTEST))
                .isSameAs(ordered);
    }

    // ---- getFlightDetails ---------------------------------------------

    @Test
    void getFlightDetails_unknownFlightYields404() {
        when(flightRepository.findByIdWithFlightModel(999L)).thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> svc.getFlightDetails(999L));
    }

    @Test
    @DisplayName("seat map: BOOKED from flight_seats (via LEFT JOIN), LOCKED from Redis (excluding booked), AVAILABLE otherwise")
    void getFlightDetails_seatStatusesComposedFromFlightSeatsPlusRedis() {
        // Q2 returns one row per template. s1 has a non-null flightSeatId
        // (a matching row exists in flight_seats), the others don't.
        SeatOccupancyRow r1 = new SeatOccupancyRow(101L, "1A", 999L);
        SeatOccupancyRow r2 = new SeatOccupancyRow(102L, "1B", null);
        SeatOccupancyRow r3 = new SeatOccupancyRow(103L, "2A", null);

        when(flightRepository.findByIdWithFlightModel(1L)).thenReturn(Optional.of(blrBom));
        when(seatRepository.findSeatOccupancy(1L, 1L)).thenReturn(List.of(r1, r2, r3));
        // s2 has a Redis lock; s3 is completely free. Redis is only asked
        // about the not-yet-booked candidates (i.e. NOT s1).
        when(seatLockService.getLockedSeatIds(eq(1L), any())).thenReturn(Set.of(102L));
        // Pricing overload takes the booked count directly — no COUNT query.
        when(flightPricingService.quoteFor(blrBom, 1L)).thenReturn(quote(new BigDecimal("3200")));

        FlightDetailsDto out = svc.getFlightDetails(1L);

        assertThat(out.flight().flightId()).isEqualTo(1L);
        assertThat(out.seats())
                .extracting(SeatDto::seatId, SeatDto::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(101L, SeatStatus.BOOKED),
                        org.assertj.core.groups.Tuple.tuple(102L, SeatStatus.LOCKED),
                        org.assertj.core.groups.Tuple.tuple(103L, SeatStatus.AVAILABLE));
    }

    @Test
    @DisplayName("seat map order comes from the SQL ORDER BY — no Java-side sort")
    void getFlightDetails_seatOrderIsPreservedFromQuery() {
        // The repo query is ORDER BY seat_number; the service just passes
        // rows through. Give the mock a pre-sorted list and assert we
        // don't re-sort or re-shuffle.
        SeatOccupancyRow r1 = new SeatOccupancyRow(102L, "1A", null);
        SeatOccupancyRow r2 = new SeatOccupancyRow(103L, "2A", null);
        SeatOccupancyRow r3 = new SeatOccupancyRow(101L, "3B", null);
        when(flightRepository.findByIdWithFlightModel(1L)).thenReturn(Optional.of(blrBom));
        when(seatRepository.findSeatOccupancy(1L, 1L)).thenReturn(List.of(r1, r2, r3));
        when(seatLockService.getLockedSeatIds(eq(1L), any())).thenReturn(Set.of());
        when(flightPricingService.quoteFor(blrBom, 0L)).thenReturn(quote(new BigDecimal("3200")));

        FlightDetailsDto out = svc.getFlightDetails(1L);

        assertThat(out.seats()).extracting(SeatDto::seatNumber).containsExactly("1A", "2A", "3B");
    }
}
