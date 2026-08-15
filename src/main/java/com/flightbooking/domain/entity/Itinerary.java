package com.flightbooking.domain.entity;

import com.flightbooking.domain.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An itinerary is the top-level unit that a caller reserves, confirms,
 * and cancels. It owns one or more {@link Booking} legs — a direct
 * flight is the degenerate case of a single-leg itinerary, a two-hop
 * trip is three-leg, and so on.
 *
 * <p><b>Why this indirection.</b> A multi-leg itinerary has to be
 * all-or-nothing at every stage: reserve either grabs seat locks on
 * every leg or none, confirm charges one payment that covers every
 * leg, cancel refunds that one payment and releases every leg's
 * {@code flight_seats} row. Trying to model that with N independent
 * {@link Booking} rows would push the atomicity into orchestration
 * code full of compensating actions. Making the itinerary the durable
 * unit lets the atomicity ride on a single {@code @Transactional}
 * boundary and a single row-level status transition.</p>
 *
 * <p><b>What moved here from Booking.</b> Everything that describes
 * the reservation session as a whole rather than the individual leg:
 * the customer ({@link #user}), the lifecycle {@link #status}, both
 * idempotency keys (reserve session + cancel session), all four
 * timestamps, the aggregated {@link #finalPrice} across all legs,
 * and the {@link Payment} that covers the trip. {@link Booking}
 * itself keeps only the fields that vary leg-by-leg — flight, seat,
 * per-leg price, and the {@link #legs leg order}.</p>
 */
@Entity
@Table(name = "itineraries", indexes = {
        @Index(name = "idx_itineraries_user", columnList = "user_id"),
        @Index(name = "idx_itineraries_idem", columnList = "idempotency_key", unique = true),
        @Index(name = "idx_itineraries_cancel_idem", columnList = "cancellation_idempotency_key", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Itinerary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The traveller. One user per itinerary — the reserve API refuses
     * a multi-leg trip that splits across users. Named FK so the DDL
     * emits {@code fk_itineraries_user} in {@code information_schema}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_itineraries_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;

    /**
     * Client-supplied {@code X-Idempotency-Key} for the entire
     * reserve/confirm session. Unique across the table — a duplicate
     * reserve short-circuits to the existing row, and confirm proves
     * the caller sent the same key before charging.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    /**
     * Client-supplied {@code X-Idempotency-Key} for the cancel
     * session. Distinct from {@link #idempotencyKey} — cancel is a
     * separate mutating call with its own fresh key. Nullable while
     * RESERVED/CONFIRMED, set once on the flip to CANCELLED. Same
     * three-way replay semantics documented on
     * {@code BookingService.cancel}.
     */
    @Column(name = "cancellation_idempotency_key", unique = true, length = 64)
    private String cancellationIdempotencyKey;

    /** Wall-clock instant the itinerary row was created (reserve time). */
    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    /**
     * Wall-clock instant the Redis seat-lock TTL fires for every leg.
     * All legs share the same TTL so the itinerary expires atomically.
     * Advisory only — the durable exclusion is the
     * {@code flight_seats} unique constraint, not this timestamp.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set on the RESERVED → CONFIRMED transition. Null while RESERVED. */
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Total price across every leg, locked in at reserve time and
     * charged verbatim at confirm. Individual leg prices live on
     * {@link Booking#getFinalPrice()}; the sum is cached here so the
     * DTO and the payment call don't need to re-add them.
     */
    @Column(name = "final_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalPrice;

    /**
     * The successful CHARGE {@link Payment} taken at confirm time —
     * one payment covers every leg. Nullable while RESERVED; set once
     * on the flip to CONFIRMED; preserved across CANCELLED so
     * {@code refund()} can find the original charge.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "payment_id",
            foreignKey = @ForeignKey(name = "fk_itineraries_payment"))
    private Payment payment;

    /**
     * The itinerary's legs, ordered by {@link Booking#getLegOrder()}.
     * Not eagerly cascaded — {@code BookingService.reserve} explicitly
     * inserts each leg after the parent itinerary exists so the FK
     * direction is unambiguous and the leg ids come back in caller
     * order.
     */
    @OneToMany(mappedBy = "itinerary", fetch = FetchType.LAZY)
    @OrderBy("legOrder ASC")
    @Builder.Default
    private List<Booking> legs = new ArrayList<>();
}
