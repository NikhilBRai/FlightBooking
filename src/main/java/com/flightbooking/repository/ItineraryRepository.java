package com.flightbooking.repository;

import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    /**
     * Powers the reserve-time idempotency short-circuit: if a client
     * retries {@code POST /itinerary/reserve} with the same
     * {@code X-Idempotency-Key} header, we return the existing
     * itinerary instead of writing a fresh row set + Redis locks.
     */
    Optional<Itinerary> findByIdempotencyKey(String idempotencyKey);

    /**
     * Does {@code userId} have any itinerary that includes a leg on
     * {@code flightId} whose status is in {@code statuses}? Powers
     * the waitlist join guard — a user with an active
     * ({@code RESERVED} or {@code CONFIRMED}) leg on a flight
     * shouldn't also be on that flight's waitlist because there's
     * nothing to wait for.
     *
     * <p>Written as an explicit {@code EXISTS} rather than a derived
     * query so the join direction (itineraries → bookings) is
     * unambiguous and the SQL plan is easy to read.</p>
     */
    @Query("""
        SELECT CASE WHEN COUNT(i) > 0 THEN true ELSE false END
          FROM Itinerary i
          JOIN i.legs b
         WHERE i.user.id = :userId
           AND b.flight.id = :flightId
           AND i.status IN :statuses
        """)
    boolean existsActiveLegForUserOnFlight(@Param("userId") Long userId,
                                           @Param("flightId") Long flightId,
                                           @Param("statuses") Collection<BookingStatus> statuses);

    /**
     * Loads an itinerary together with every association the booking
     * flow reads, in a single query. Used by
     * {@code BookingService.confirm}, {@code .cancel}, and
     * {@code .getItinerary}, which navigate:
     *
     * <ul>
     *   <li>{@code itinerary.user} — notify recipient, ownership check</li>
     *   <li>{@code itinerary.legs} — every {@link com.flightbooking.domain.entity.Booking}
     *       row for the trip</li>
     *   <li>{@code leg.flight} + {@code leg.flight.flightModel} —
     *       waitlist notify, fullyBooked flip, DTO airport pair,
     *       total-seats compare on confirm</li>
     *   <li>{@code leg.seat} — DTO seat number, {@code flight_seats}
     *       insert / delete</li>
     *   <li>{@code itinerary.payment} — refund routing (LEFT because
     *       RESERVED itineraries have no payment yet)</li>
     * </ul>
     *
     * <p>The {@code legs} join is a {@code @OneToMany} so the result
     * set has one row per leg. Hibernate deduplicates the parent by
     * id — we return the single {@link Itinerary} with its
     * {@code legs} collection populated. {@code LEFT JOIN} on
     * {@code legs} keeps the query correct even for an itinerary
     * with a broken legs list (shouldn't happen, but the SQL doesn't
     * disappear if it does).</p>
     */
    @Query("""
        SELECT DISTINCT i
          FROM Itinerary i
          JOIN FETCH i.user
          LEFT JOIN FETCH i.legs leg
          LEFT JOIN FETCH leg.flight f
          LEFT JOIN FETCH f.flightModel
          LEFT JOIN FETCH leg.seat
          LEFT JOIN FETCH i.payment
         WHERE i.id = :id
        """)
    Optional<Itinerary> findByIdWithGraph(@Param("id") Long id);
}
