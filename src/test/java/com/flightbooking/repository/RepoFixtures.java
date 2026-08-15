package com.flightbooking.repository;

import com.flightbooking.domain.entity.Booking;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.FlightSeat;
import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Payment;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.entity.WaitlistEntry;
import com.flightbooking.domain.enums.BookingStatus;
import com.flightbooking.domain.enums.PaymentStatus;
import com.flightbooking.domain.enums.PaymentType;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Tiny fixture helper used by every @DataJpaTest so per-test setUp
 * stays under a screen — persists a valid entity graph without the
 * boilerplate leaking into the assertion bodies.
 *
 * <p>Every method flushes so subsequent queries see the row without
 * relying on JPA's dirty-checking timing.</p>
 */
final class RepoFixtures {

    private final EntityManager em;

    RepoFixtures(EntityManager em) {
        this.em = em;
    }

    User user(String name, String email) {
        User u = User.builder().name(name).email(email).build();
        em.persist(u);
        em.flush();
        return u;
    }

    FlightModel flightModel(String make, int seats) {
        FlightModel m = FlightModel.builder().make(make).totalSeats(seats).build();
        em.persist(m);
        em.flush();
        return m;
    }

    Seat seat(FlightModel model, String seatNumber) {
        Seat s = Seat.builder().flightModel(model).seatNumber(seatNumber).build();
        em.persist(s);
        em.flush();
        return s;
    }

    Flight flight(FlightModel model, String src, String dst, Instant start, Duration dur, BigDecimal cost) {
        Flight f = Flight.builder()
                .flightModel(model).source(src).destination(dst)
                .startTime(start).endTime(start.plus(dur))
                .cost(cost).fullyBooked(false).build();
        em.persist(f);
        em.flush();
        return f;
    }

    /**
     * Persist an itinerary in the given status with the given
     * reserve-session key. Legs are added separately via
     * {@link #booking(Itinerary, Flight, Seat, int)}.
     */
    Itinerary itinerary(User user, BookingStatus status, String idempotencyKey, BigDecimal finalPrice) {
        Instant now = Instant.now();
        Itinerary it = Itinerary.builder()
                .user(user).status(status).idempotencyKey(idempotencyKey)
                .reservedAt(now).expiresAt(now.plus(Duration.ofMinutes(5)))
                .finalPrice(finalPrice).build();
        em.persist(it);
        em.flush();
        return it;
    }

    /**
     * Persist one leg attached to {@code itinerary} at the given
     * {@code legOrder} slot with a per-leg finalPrice of 1000.
     */
    Booking booking(Itinerary itinerary, Flight flight, Seat seat, int legOrder) {
        Booking b = Booking.builder()
                .itinerary(itinerary).legOrder(legOrder)
                .flight(flight).seat(seat)
                .finalPrice(new BigDecimal("1000"))
                .build();
        em.persist(b);
        em.flush();
        return b;
    }

    FlightSeat flightSeat(Flight flight, Seat seat) {
        FlightSeat fs = FlightSeat.builder()
                .flight(flight).seat(seat).bookedAt(Instant.now()).build();
        em.persist(fs);
        em.flush();
        return fs;
    }

    WaitlistEntry waitlist(Flight flight, User user, Instant addedAt) {
        WaitlistEntry w = WaitlistEntry.builder()
                .flight(flight).user(user).addedAt(addedAt).build();
        em.persist(w);
        em.flush();
        return w;
    }

    Payment payment(Itinerary itinerary, PaymentType type, String idempotencyKey, String txnId) {
        Payment p = Payment.builder()
                .itinerary(itinerary).type(type).status(PaymentStatus.SUCCESS)
                .idempotencyKey(idempotencyKey).transactionId(txnId)
                .amount(new BigDecimal("500")).paymentMethod("card")
                .createdAt(Instant.now()).build();
        em.persist(p);
        em.flush();
        return p;
    }
}
