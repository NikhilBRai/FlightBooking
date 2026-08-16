package com.flightbooking.repository;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.FlightSeat;
import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.enums.BookingStatus;
import com.flightbooking.repository.FlightSeatRepository.FlightSeatCount;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
class FlightSeatRepositoryTest {

    @Autowired FlightSeatRepository flightSeatRepository;
    @Autowired EntityManager em;

    private RepoFixtures fix;
    private Flight flightA;
    private Flight flightB;
    private Seat seat1;
    private Seat seat2;
    private Seat seat3;

    @BeforeEach
    void setUp() {
        fix = new RepoFixtures(em);
        FlightModel model = fix.flightModel("Boeing", 6);
        seat1 = fix.seat(model, "1A");
        seat2 = fix.seat(model, "1B");
        seat3 = fix.seat(model, "2A");
        flightA = fix.flight(model, "BLR", "BOM",
                Instant.parse("2030-01-01T08:00:00Z"), Duration.ofHours(2), new BigDecimal("3200"));
        flightB = fix.flight(model, "BLR", "HYD",
                Instant.parse("2030-01-01T09:00:00Z"), Duration.ofMinutes(90), new BigDecimal("2200"));
    }

    @Test
    void findByFlight_id_returnsBookedSeatsForThatFlightOnly() {
        fix.flightSeat(flightA, seat1);
        fix.flightSeat(flightA, seat2);
        fix.flightSeat(flightB, seat3);

        List<FlightSeat> onA = flightSeatRepository.findByFlight_Id(flightA.getId());
        List<FlightSeat> onB = flightSeatRepository.findByFlight_Id(flightB.getId());

        assertThat(onA).hasSize(2).extracting(fs -> fs.getSeat().getSeatNumber())
                .containsExactlyInAnyOrder("1A", "1B");
        assertThat(onB).hasSize(1);
    }

    @Test
    void existsByFlight_seat_trueOnlyWhenExactRowPresent() {
        fix.flightSeat(flightA, seat1);

        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightA.getId(), seat1.getId())).isTrue();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightA.getId(), seat2.getId())).isFalse();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightB.getId(), seat1.getId())).isFalse();
    }

    @Test
    void countByFlight_id_matches_findByFlight_id_size() {
        fix.flightSeat(flightA, seat1);
        fix.flightSeat(flightA, seat2);
        assertThat(flightSeatRepository.countByFlight_Id(flightA.getId())).isEqualTo(2L);
        assertThat(flightSeatRepository.countByFlight_Id(flightB.getId())).isZero();
    }

    @Test
    void deleteByFlight_seat_removesExactlyOneRow() {
        fix.flightSeat(flightA, seat1);
        fix.flightSeat(flightA, seat2);

        long removed = flightSeatRepository.deleteByFlight_IdAndSeat_Id(flightA.getId(), seat1.getId());
        em.flush();
        em.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(flightSeatRepository.countByFlight_Id(flightA.getId())).isEqualTo(1L);
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightA.getId(), seat1.getId())).isFalse();
    }

    @Test
    void countBookedByFlightIds_batchedGroupBy_returnsOneRowPerFlight() {
        fix.flightSeat(flightA, seat1);
        fix.flightSeat(flightA, seat2);
        fix.flightSeat(flightB, seat3);

        List<FlightSeatCount> rows = flightSeatRepository.countBookedByFlightIds(
                List.of(flightA.getId(), flightB.getId()));

        Map<Long, Long> byId = rows.stream()
                .collect(Collectors.toMap(FlightSeatCount::getFlightId, FlightSeatCount::getSeatCount));
        assertThat(byId).containsEntry(flightA.getId(), 2L).containsEntry(flightB.getId(), 1L);
    }

    @Test
    void countBookedByFlightIds_omitsFlightsWithZeroBookings() {
        fix.flightSeat(flightA, seat1);
        // flightB has no bookings — must NOT appear in the projection result
        // at all (LEFT JOIN would return it as 0, but our GROUP BY over
        // FlightSeat only surfaces flights with at least one booked seat).
        List<FlightSeatCount> rows = flightSeatRepository.countBookedByFlightIds(
                List.of(flightA.getId(), flightB.getId()));
        assertThat(rows).extracting(FlightSeatCount::getFlightId).containsExactly(flightA.getId());
    }

    @Test
    void findBookedSeatIdsByFlight_returnsExactlySeatIdsForThatFlight() {
        fix.flightSeat(flightA, seat1);
        fix.flightSeat(flightA, seat2);
        fix.flightSeat(flightB, seat3); // must NOT leak into flightA's list

        List<Long> idsA = flightSeatRepository.findBookedSeatIdsByFlight_Id(flightA.getId());
        List<Long> idsB = flightSeatRepository.findBookedSeatIdsByFlight_Id(flightB.getId());

        assertThat(idsA).containsExactlyInAnyOrder(seat1.getId(), seat2.getId());
        assertThat(idsB).containsExactly(seat3.getId());
    }

    @Test
    void findBookedSeatIdsByFlight_emptyForFlightWithNoBookings() {
        assertThat(flightSeatRepository.findBookedSeatIdsByFlight_Id(flightA.getId())).isEmpty();
    }

    // ---- deleteAllByItinerary_Id (bulk cancel) ------------------------

    @Test
    void deleteAllByItinerary_removesExactlyTheFlightSeatsMatchingItsLegs() {
        // Two itineraries share flightA. Only itinerary1's leg rows
        // should disappear when we cancel itinerary1; itinerary2 must
        // be untouched. This is exactly the correctness property the
        // EXISTS-subquery delete guarantees over a naïve "delete by
        // (flight_id, seat_id) IN (...)" tuple-IN, which could match
        // other users' bookings.
        User u1 = fix.user("Alice", "a@e");
        User u2 = fix.user("Bob", "b@e");
        Itinerary itin1 = fix.itinerary(u1, BookingStatus.CONFIRMED,
                "itin1-key", new BigDecimal("6000"));
        Itinerary itin2 = fix.itinerary(u2, BookingStatus.CONFIRMED,
                "itin2-key", new BigDecimal("2000"));
        // itin1 legs: (flightA, seat1) + (flightB, seat2). legOrder
        // is unique within an itinerary — 0 and 1.
        // itin2 leg:  (flightA, seat3) — same flight, different seat.
        fix.booking(itin1, flightA, seat1, 0);
        fix.booking(itin1, flightB, seat2, 1);
        fix.booking(itin2, flightA, seat3, 0);
        fix.flightSeat(flightA, seat1);
        fix.flightSeat(flightB, seat2);
        fix.flightSeat(flightA, seat3);

        int removed = flightSeatRepository.deleteAllByItinerary_Id(itin1.getId());
        em.flush();
        em.clear();

        assertThat(removed).isEqualTo(2);
        // itin1's rows are gone; itin2's row (flightA, seat3) is still there.
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightA.getId(), seat1.getId())).isFalse();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightB.getId(), seat2.getId())).isFalse();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(flightA.getId(), seat3.getId())).isTrue();
    }

    @Test
    void deleteAllByItinerary_returnsZeroWhenNoLegHasFlightSeatRow() {
        // Itinerary in RESERVED state (or any state where confirm
        // never inserted flight_seats). Cancel-side delete must be
        // a no-op — no rows to remove, no exception.
        User u = fix.user("Alice", "a@e");
        Itinerary reserved = fix.itinerary(u, BookingStatus.RESERVED,
                "unfulfilled", new BigDecimal("3200"));
        fix.booking(reserved, flightA, seat1, 0);

        int removed = flightSeatRepository.deleteAllByItinerary_Id(reserved.getId());
        assertThat(removed).isZero();
    }

    @Test
    void deleteAllByItinerary_ignoresUnknownItineraryId() {
        // Sanity: unknown id must not throw, must remove nothing.
        assertThat(flightSeatRepository.deleteAllByItinerary_Id(999_999L)).isZero();
    }

    @Test
    void uniqueConstraintPreventsDoubleBookingSameSeatOnSameFlight() {
        fix.flightSeat(flightA, seat1);
        FlightSeat dup = FlightSeat.builder()
                .flight(flightA).seat(seat1).bookedAt(Instant.now()).build();
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> {
            em.persist(dup);
            em.flush();
        });
        assertThat(t).isNotNull();
    }
}
