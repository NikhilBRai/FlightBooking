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
