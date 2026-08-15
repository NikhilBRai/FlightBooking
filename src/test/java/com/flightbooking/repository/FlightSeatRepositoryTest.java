package com.flightbooking.repository;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.FlightSeat;
import com.flightbooking.domain.entity.Seat;
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
