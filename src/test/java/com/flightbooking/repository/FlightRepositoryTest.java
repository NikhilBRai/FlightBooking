package com.flightbooking.repository;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
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
 * Exercises every custom @Query on FlightRepository. Data set is a
 * tiny synthetic graph that hits every filter (destination match,
 * time-window boundary, fullyBooked exclusion) at least once so a
 * regression in the WHERE clauses fails loudly.
 */
@DataJpaTest
@AutoConfigureTestDatabase
class FlightRepositoryTest {

    @Autowired FlightRepository flightRepository;
    @Autowired EntityManager em;

    private RepoFixtures fix;
    private FlightModel model;
    private final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        fix = new RepoFixtures(em);
        model = fix.flightModel("Boeing", 6);
    }

    private Flight direct(String src, String dst, int hourStart, int minutes, boolean fullyBooked) {
        Flight f = fix.flight(model, src, dst,
                T0.plus(Duration.ofHours(hourStart)),
                Duration.ofMinutes(minutes),
                new BigDecimal("1000"));
        if (fullyBooked) {
            f.setFullyBooked(true);
            em.merge(f);
            em.flush();
        }
        return f;
    }

    // ---- findInboundInWindow ------------------------------------------

    @Test
    void findInboundInWindow_returnsMatchingDestinationInsideWindow() {
        Flight inWindow = direct("BLR", "BOM", 8, 120, false);
        Flight wrongDest = direct("BLR", "HYD", 8, 90, false);

        Instant start = T0;
        Instant end = T0.plus(Duration.ofDays(1));

        List<Flight> inbound = flightRepository.findInboundInWindow("BOM", start, end);

        assertThat(inbound).extracting(Flight::getId).containsExactly(inWindow.getId());
        assertThat(inbound).extracting(Flight::getId).doesNotContain(wrongDest.getId());
    }

    @Test
    void findInboundInWindow_excludesFullyBookedFlights() {
        Flight full = direct("BLR", "BOM", 8, 120, true);
        Flight avail = direct("BLR", "BOM", 10, 120, false);

        List<Flight> inbound = flightRepository.findInboundInWindow(
                "BOM", T0, T0.plus(Duration.ofDays(1)));

        assertThat(inbound).extracting(Flight::getId).containsExactly(avail.getId());
    }

    @Test
    void findInboundInWindow_startBoundaryInclusive_endExclusive() {
        Flight atStart = direct("BLR", "BOM", 0, 120, false);   // startTime == windowStart
        Flight atEndMinus = direct("BLR", "BOM", 23, 60, false); // inside
        Instant windowStart = T0;
        Instant windowEnd = T0.plus(Duration.ofDays(1));

        List<Flight> inbound = flightRepository.findInboundInWindow("BOM", windowStart, windowEnd);

        assertThat(inbound).extracting(Flight::getId)
                .containsExactlyInAnyOrder(atStart.getId(), atEndMinus.getId());

        // A flight departing right on windowEnd is excluded (< end).
        Flight atEnd = direct("BLR", "BOM", 24, 60, false);
        assertThat(flightRepository.findInboundInWindow("BOM", windowStart, windowEnd))
                .extracting(Flight::getId).doesNotContain(atEnd.getId());
    }

    // ---- findLandingAtHubInWindow ------------------------------------

    @Test
    void findLandingAtHubInWindow_filtersByHubAndLandingWindow_excludesFullyBooked() {
        Flight hit = direct("BLR", "HYD", 8, 60, false);   // lands 09:00
        Flight fullOnHub = direct("BLR", "HYD", 9, 60, true); // lands 10:00 but full
        Flight wrongHub = direct("BLR", "MAA", 8, 60, false);

        Instant earliest = T0.plus(Duration.ofHours(8).plusMinutes(30));
        Instant latest = T0.plus(Duration.ofHours(11));

        List<Flight> out = flightRepository.findLandingAtHubInWindow("HYD", earliest, latest);

        assertThat(out).extracting(Flight::getId).containsExactly(hit.getId());
        assertThat(out).extracting(Flight::getId).doesNotContain(fullOnHub.getId(), wrongHub.getId());
    }

    // ---- findFeederLegs -----------------------------------------------

    @Test
    void findFeederLegs_strictlyFiltersSourceAndDestinationAndWindow() {
        Flight hit = direct("BLR", "HYD", 8, 60, false); // lands 09:00
        Flight wrongSrc = direct("MAA", "HYD", 8, 60, false);
        Flight wrongDest = direct("BLR", "BOM", 8, 60, false);

        Instant earliest = T0.plus(Duration.ofHours(8).plusMinutes(30));
        Instant latest = T0.plus(Duration.ofHours(11));

        List<Flight> out = flightRepository.findFeederLegs("BLR", "HYD", earliest, latest);

        assertThat(out).extracting(Flight::getId).containsExactly(hit.getId());
        assertThat(out).extracting(Flight::getId).doesNotContain(wrongSrc.getId(), wrongDest.getId());
    }

    // ---- findAllLandingInWindow ---------------------------------------

    @Test
    void findAllLandingInWindow_returnsEveryFlightLandingInsideRange_regardlessOfHub() {
        Flight a = direct("BLR", "HYD", 8, 60, false);  // lands 09:00
        Flight b = direct("MAA", "HYD", 8, 60, false);  // lands 09:00 different src
        Flight c = direct("BLR", "BOM", 8, 60, false);  // lands 09:00 different dst
        Flight full = direct("BLR", "BOM", 9, 60, true); // fully booked, excluded

        Instant earliest = T0.plus(Duration.ofHours(8).plusMinutes(30));
        Instant latest = T0.plus(Duration.ofHours(11));

        List<Flight> out = flightRepository.findAllLandingInWindow(earliest, latest);

        assertThat(out).extracting(Flight::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        assertThat(out).extracting(Flight::getId).doesNotContain(full.getId());
    }

    @Test
    void findAllLandingInWindow_latestLandingIsInclusive() {
        // Query semantic is [earliestLanding, latestLanding] — both
        // sides closed — so a flight landing exactly at latestLanding
        // is included. Matches the per-path filter in
        // BatchedFlightSearchStrategy, which uses isAfter/isBefore
        // (strict > / <), so a feeder landing exactly at
        // maxSpineStart - minLayover is still a valid candidate.
        Flight boundary = direct("BLR", "HYD", 10, 60, false); // lands exactly at latest
        Instant latest = T0.plus(Duration.ofHours(11));
        List<Flight> out = flightRepository.findAllLandingInWindow(T0, latest);
        assertThat(out).extracting(Flight::getId).contains(boundary.getId());
    }

    // ---- findByIdWithFlightModel --------------------------------------

    @Test
    void findByIdWithFlightModel_eagerlyInitializesTheModelAssociation() {
        Flight f = direct("BLR", "BOM", 8, 120, false);
        em.clear(); // evict the persistence context so lazy loading is really tested

        Optional<Flight> loaded = flightRepository.findByIdWithFlightModel(f.getId());

        assertThat(loaded).isPresent();
        // JOIN FETCH means the proxy is already initialised — no
        // second round-trip is needed to read .getMake() /
        // .getTotalSeats(). If someone regresses the query to a plain
        // findById this assertion would still pass in-tx (lazy load
        // fires) but Hibernate.isInitialized would report false.
        assertThat(Hibernate.isInitialized(loaded.get().getFlightModel())).isTrue();
        assertThat(loaded.get().getFlightModel().getMake()).isEqualTo("Boeing");
        assertThat(loaded.get().getFlightModel().getTotalSeats()).isEqualTo(6);
    }

    @Test
    void findByIdWithFlightModel_returnsEmptyForUnknownId() {
        assertThat(flightRepository.findByIdWithFlightModel(999L)).isEmpty();
    }

    // ---- JOIN FETCH invariants for the search queries -----------------
    //
    // Every search-time query is expected to eagerly initialise
    // f.flightModel; otherwise pricing (needs totalSeats) and DTO
    // mapping (needs make) each fire one lazy SELECT per unique flight,
    // and the "two round-trips per search" contract silently becomes
    // 2 + N. These tests clear the persistence context so a plain
    // findXxx() would return an uninitialised proxy.

    @Test
    void findInboundInWindow_eagerlyInitialisesFlightModel() {
        direct("BLR", "BOM", 8, 120, false);
        em.clear();

        List<Flight> out = flightRepository.findInboundInWindow(
                "BOM", T0, T0.plus(Duration.ofDays(1)));

        assertThat(out).hasSize(1);
        assertThat(Hibernate.isInitialized(out.get(0).getFlightModel())).isTrue();
    }

    @Test
    void findLandingAtHubInWindow_eagerlyInitialisesFlightModel() {
        direct("BLR", "HYD", 8, 60, false);
        em.clear();

        List<Flight> out = flightRepository.findLandingAtHubInWindow(
                "HYD",
                T0.plus(Duration.ofHours(8).plusMinutes(30)),
                T0.plus(Duration.ofHours(11)));

        assertThat(out).hasSize(1);
        assertThat(Hibernate.isInitialized(out.get(0).getFlightModel())).isTrue();
    }

    @Test
    void findFeederLegs_eagerlyInitialisesFlightModel() {
        direct("BLR", "HYD", 8, 60, false);
        em.clear();

        List<Flight> out = flightRepository.findFeederLegs(
                "BLR", "HYD",
                T0.plus(Duration.ofHours(8).plusMinutes(30)),
                T0.plus(Duration.ofHours(11)));

        assertThat(out).hasSize(1);
        assertThat(Hibernate.isInitialized(out.get(0).getFlightModel())).isTrue();
    }

    @Test
    void findAllLandingInWindow_eagerlyInitialisesFlightModel() {
        direct("BLR", "HYD", 8, 60, false);
        direct("MAA", "HYD", 8, 60, false);
        em.clear();

        List<Flight> out = flightRepository.findAllLandingInWindow(
                T0.plus(Duration.ofHours(8).plusMinutes(30)),
                T0.plus(Duration.ofHours(11)));

        assertThat(out).hasSize(2);
        assertThat(out).allSatisfy(
                f -> assertThat(Hibernate.isInitialized(f.getFlightModel())).isTrue());
    }
}
