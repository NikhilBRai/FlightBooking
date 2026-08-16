package com.flightbooking.repository;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Seat;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consolidated seat-map + occupancy query is what lets
 * {@code FlightService.getFlightDetails} run in two SQL round-trips
 * instead of three, so it deserves its own repo test. Covers:
 * <ul>
 *   <li>booked vs unbooked resolution via the LEFT JOIN (nullable
 *       {@code flightSeatId}),</li>
 *   <li>scoping to the requested flight — a booking on a <em>different</em>
 *       flight on the same aircraft model must not leak in,</li>
 *   <li>scoping to the requested flight model — a seat template from a
 *       different model must not appear in the result,</li>
 *   <li>SQL-side ordering by seat number so callers don't need to
 *       re-sort in Java.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase
class SeatRepositoryTest {

    @Autowired SeatRepository seatRepository;
    @Autowired EntityManager em;

    private RepoFixtures fix;
    private FlightModel boeing;
    private FlightModel airbus;
    private Flight blrBom;
    private Flight blrHyd;
    private Seat s1a;
    private Seat s1b;
    private Seat s2a;
    private Seat airbusSeat;

    @BeforeEach
    void setUp() {
        fix = new RepoFixtures(em);
        boeing = fix.flightModel("Boeing", 3);
        airbus = fix.flightModel("Airbus", 1);
        // Deliberately out of natural order so the ORDER BY has
        // something to actually reorder.
        s2a = fix.seat(boeing, "2A");
        s1b = fix.seat(boeing, "1B");
        s1a = fix.seat(boeing, "1A");
        airbusSeat = fix.seat(airbus, "1A");
        Instant t0 = Instant.parse("2030-01-01T00:00:00Z");
        blrBom = fix.flight(boeing, "BLR", "BOM", t0, Duration.ofHours(2), new BigDecimal("3200"));
        blrHyd = fix.flight(boeing, "BLR", "HYD", t0, Duration.ofHours(1), new BigDecimal("2200"));
    }

    @Test
    void bookedSeatOnRequestedFlightIsMarkedBooked_others_null() {
        fix.flightSeat(blrBom, s1a); // s1a booked on this flight

        List<SeatOccupancyRow> rows = seatRepository.findSeatOccupancy(
                blrBom.getId(), boeing.getId());

        assertThat(rows).hasSize(3);
        // Ordered by seatNumber (1A, 1B, 2A)
        assertThat(rows).extracting(SeatOccupancyRow::seatNumber).containsExactly("1A", "1B", "2A");
        // Only the row that has a flight_seats entry for THIS flight is booked.
        assertThat(rows.get(0).isBooked()).isTrue();
        assertThat(rows.get(1).isBooked()).isFalse();
        assertThat(rows.get(2).isBooked()).isFalse();
        assertThat(rows.get(0).flightSeatId()).isNotNull();
        assertThat(rows.get(1).flightSeatId()).isNull();
    }

    @Test
    void bookingOnDifferentFlight_sameAircraftModel_doesNotLeakAcross() {
        // s1a is booked on blrHyd, NOT on blrBom. Asking about blrBom
        // must show every seat as available — the LEFT JOIN filters
        // by fs.flight.id, so the fs row for blrHyd is ignored.
        fix.flightSeat(blrHyd, s1a);

        List<SeatOccupancyRow> rows = seatRepository.findSeatOccupancy(
                blrBom.getId(), boeing.getId());

        assertThat(rows).extracting(SeatOccupancyRow::isBooked)
                .containsExactly(false, false, false);
    }

    @Test
    void differentFlightModelSeatsAreNotIncluded() {
        // The Airbus seat is on a different flight model — it must
        // not appear in a Boeing seat map query, even though its
        // seat_number ("1A") collides with a Boeing seat.
        List<SeatOccupancyRow> rows = seatRepository.findSeatOccupancy(
                blrBom.getId(), boeing.getId());

        assertThat(rows).extracting(SeatOccupancyRow::seatId)
                .doesNotContain(airbusSeat.getId())
                .containsExactlyInAnyOrder(s1a.getId(), s1b.getId(), s2a.getId());
    }

    @Test
    void emptyLayoutForUnknownFlightModelId() {
        assertThat(seatRepository.findSeatOccupancy(blrBom.getId(), 999L)).isEmpty();
    }

    // ---- findSeatOccupancyForFlights (bulk) --------------------------

    @Test
    void bulkOccupancy_returnsOneRowPerSeatPerRequestedFlight() {
        // s1a booked on blrHyd only. Bulk query for BOTH flights
        // should surface s1a as booked on blrHyd and unbooked on
        // blrBom — the LEFT JOIN condition scopes fs to (flight,
        // seat), so no leakage.
        fix.flightSeat(blrHyd, s1a);

        List<SeatOccupancyRow> rows = seatRepository.findSeatOccupancyForFlights(
                List.of(blrBom.getId(), blrHyd.getId()));

        // 2 flights × 3 seats per Boeing model = 6 rows.
        assertThat(rows).hasSize(6);
        // Every row carries its flightId so callers can group.
        assertThat(rows).allSatisfy(r -> assertThat(r.flightId()).isNotNull());

        List<SeatOccupancyRow> forBlrHyd = rows.stream()
                .filter(r -> r.flightId().equals(blrHyd.getId()))
                .toList();
        List<SeatOccupancyRow> forBlrBom = rows.stream()
                .filter(r -> r.flightId().equals(blrBom.getId()))
                .toList();
        assertThat(forBlrHyd).extracting(SeatOccupancyRow::isBooked)
                .containsExactly(true, false, false); // 1A booked, 1B/2A free
        assertThat(forBlrBom).extracting(SeatOccupancyRow::isBooked)
                .containsExactly(false, false, false); // 1A free on this flight
    }

    @Test
    void bulkOccupancy_orderedByFlightIdThenSeatNumber() {
        // Ordering is a query-level guarantee so callers can groupBy
        // without an extra sort pass.
        List<SeatOccupancyRow> rows = seatRepository.findSeatOccupancyForFlights(
                List.of(blrHyd.getId(), blrBom.getId()));

        // Rows for the smaller flight_id land first, and within
        // each flight the seats come out in seatNumber order
        // (1A, 1B, 2A).
        long smallerFlightId = Math.min(blrBom.getId(), blrHyd.getId());
        assertThat(rows.get(0).flightId()).isEqualTo(smallerFlightId);
        assertThat(rows.get(2).flightId()).isEqualTo(smallerFlightId);
        assertThat(rows.subList(0, 3)).extracting(SeatOccupancyRow::seatNumber)
                .containsExactly("1A", "1B", "2A");
        assertThat(rows.subList(3, 6)).extracting(SeatOccupancyRow::seatNumber)
                .containsExactly("1A", "1B", "2A");
    }

    @Test
    void bulkOccupancy_unknownFlightIdIsSilentlyOmitted() {
        // Passing an id that doesn't exist must not blow up — the
        // query filters via IN, unknown ids simply produce no rows.
        List<SeatOccupancyRow> rows = seatRepository.findSeatOccupancyForFlights(
                List.of(blrBom.getId(), 999_999L));

        // Only rows for the known flight surface.
        assertThat(rows).allSatisfy(
                r -> assertThat(r.flightId()).isEqualTo(blrBom.getId()));
        assertThat(rows).hasSize(3);
    }

    @Test
    void bulkOccupancy_singletonInputMatchesSingleFlightQuery() {
        // Sanity check: for a single flight, the bulk shape returns
        // exactly the same seats (in the same order) as the legacy
        // single-flight query. The only structural difference is the
        // extra flightId column, which the single-flight query
        // leaves null.
        fix.flightSeat(blrBom, s1b);

        List<SeatOccupancyRow> bulk = seatRepository.findSeatOccupancyForFlights(
                List.of(blrBom.getId()));
        List<SeatOccupancyRow> single = seatRepository.findSeatOccupancy(
                blrBom.getId(), boeing.getId());

        assertThat(bulk).extracting(
                        SeatOccupancyRow::seatId, SeatOccupancyRow::seatNumber, SeatOccupancyRow::isBooked)
                .containsExactlyElementsOf(single.stream()
                        .map(r -> org.assertj.core.groups.Tuple.tuple(
                                r.seatId(), r.seatNumber(), r.isBooked()))
                        .toList());
    }

    // ---- findByIdWithFlightModel --------------------------------------

    @Test
    void findByIdWithFlightModel_eagerlyInitializesTheModelAssociation() {
        em.clear();

        Optional<Seat> loaded = seatRepository.findByIdWithFlightModel(s1a.getId());

        assertThat(loaded).isPresent();
        assertThat(Hibernate.isInitialized(loaded.get().getFlightModel())).isTrue();
        assertThat(loaded.get().getFlightModel().getId()).isEqualTo(boeing.getId());
    }

    @Test
    void findByIdWithFlightModel_returnsEmptyForUnknownSeat() {
        assertThat(seatRepository.findByIdWithFlightModel(999L)).isEmpty();
    }
}
