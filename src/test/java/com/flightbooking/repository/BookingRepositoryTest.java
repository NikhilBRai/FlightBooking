package com.flightbooking.repository;

import com.flightbooking.domain.entity.Booking;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.enums.BookingStatus;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bulk of the booking-flow reads now go through
 * {@link ItineraryRepository#findByIdWithGraph}. This suite covers the
 * narrow slice of leg-scoped queries that survived the itinerary
 * refactor plus the leg-side schema invariants (FK, unique
 * (itinerary_id, leg_order)).
 */
@DataJpaTest
@AutoConfigureTestDatabase
class BookingRepositoryTest {

    @Autowired BookingRepository bookingRepository;
    @Autowired EntityManager em;

    private RepoFixtures fix;
    private User alice;
    private Flight flightA;
    private Flight flightB;
    private Seat seatA;
    private Seat seatB;

    @BeforeEach
    void setUp() {
        fix = new RepoFixtures(em);
        alice = fix.user("Alice", "a@e");
        FlightModel model = fix.flightModel("Boeing", 6);
        seatA = fix.seat(model, "1A");
        seatB = fix.seat(model, "1B");
        flightA = fix.flight(model, "BLR", "BOM",
                Instant.parse("2030-01-01T08:00:00Z"), Duration.ofHours(2), new BigDecimal("3200"));
        flightB = fix.flight(model, "BLR", "HYD",
                Instant.parse("2030-01-01T09:00:00Z"), Duration.ofMinutes(90), new BigDecimal("2200"));
    }

    @Test
    void findByFlight_Id_returnsEveryLegOnThatFlight() {
        Itinerary it1 = fix.itinerary(alice, BookingStatus.RESERVED, "k-1", new BigDecimal("1000"));
        fix.booking(it1, flightA, seatA, 0);
        Itinerary it2 = fix.itinerary(alice, BookingStatus.RESERVED, "k-2", new BigDecimal("1000"));
        fix.booking(it2, flightA, seatB, 0);
        Itinerary it3 = fix.itinerary(alice, BookingStatus.RESERVED, "k-3", new BigDecimal("1000"));
        fix.booking(it3, flightB, seatA, 0);

        List<Booking> onA = bookingRepository.findByFlight_Id(flightA.getId());
        assertThat(onA).hasSize(2)
                .extracting(b -> b.getSeat().getSeatNumber())
                .containsExactlyInAnyOrder("1A", "1B");

        List<Booking> onB = bookingRepository.findByFlight_Id(flightB.getId());
        assertThat(onB).hasSize(1);
    }

    @Test
    void findByFlight_Id_unknownFlightReturnsEmpty() {
        assertThat(bookingRepository.findByFlight_Id(9999L)).isEmpty();
    }

    @Test
    void legOrderUniqueWithinItinerary_secondLegAtSameOrderSlotFails() {
        // (itinerary_id, leg_order) is unique — a second leg claiming
        // slot 0 within the same itinerary must fail at flush time.
        Itinerary it = fix.itinerary(alice, BookingStatus.RESERVED, "k-uniq", new BigDecimal("2000"));
        fix.booking(it, flightA, seatA, 0);

        Booking dup = Booking.builder()
                .itinerary(it).legOrder(0)
                .flight(flightB).seat(seatB)
                .finalPrice(new BigDecimal("1000"))
                .build();

        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> {
            em.persist(dup);
            em.flush();
        });
        assertThat(t)
                .as("second leg at leg_order=0 must trip the (itinerary_id, leg_order) unique index")
                .isNotNull();
    }

    @Test
    void bookingWithoutItineraryFailsFK() {
        // itinerary is non-null on the entity; a leg without a
        // parent itinerary must fail at flush time.
        Booking orphan = Booking.builder()
                .legOrder(0).flight(flightA).seat(seatA)
                .finalPrice(new BigDecimal("1000"))
                .build();

        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> {
            em.persist(orphan);
            em.flush();
        });
        assertThat(t).isNotNull();
    }

    @Test
    void multiLegItineraryPersistsAllLegs() {
        Itinerary it = fix.itinerary(alice, BookingStatus.RESERVED, "k-multi", new BigDecimal("6000"));
        fix.booking(it, flightA, seatA, 0);
        fix.booking(it, flightB, seatB, 1);

        List<Booking> all = bookingRepository.findAll();
        assertThat(all).hasSize(2)
                .allSatisfy(b -> assertThat(b.getItinerary().getId()).isEqualTo(it.getId()))
                .extracting(Booking::getLegOrder)
                .containsExactlyInAnyOrder(0, 1);
    }
}
