package com.flightbooking.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * One leg of an {@link Itinerary} — a single (flight, seat)
 * assignment. A direct-flight itinerary has exactly one Booking; a
 * two-hop itinerary has two, ordered by {@link #legOrder}.
 *
 * <p>Session-level concerns — status, both idempotency keys, the
 * payment, and all four lifecycle timestamps — live on the parent
 * {@link Itinerary}, not here. Legs share those attributes by design:
 * you can't have leg 1 CONFIRMED while leg 2 is still RESERVED, and
 * you can't cancel one leg of a two-hop trip. The {@link Booking}
 * row is deliberately narrow: what flight, what seat, at what
 * per-leg price, and where in the trip it sits.</p>
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_itinerary", columnList = "itinerary_id"),
        @Index(name = "idx_bookings_flight", columnList = "flight_id"),
        @Index(name = "idx_bookings_seat", columnList = "seat_id"),
        @Index(name = "uk_bookings_itin_order", columnList = "itinerary_id, leg_order", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Parent itinerary. Non-null — every leg belongs to exactly one
     * itinerary. Named FK so the DDL emits
     * {@code fk_bookings_itinerary}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "itinerary_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_bookings_itinerary"))
    private Itinerary itinerary;

    /**
     * Zero-based position of this leg within the itinerary — 0 for
     * the first leg, 1 for the second, etc. Uniqueness on
     * {@code (itinerary_id, leg_order)} guarantees no two legs share
     * a slot.
     */
    @Column(name = "leg_order", nullable = false)
    private int legOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "flight_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_bookings_flight"))
    private Flight flight;

    /**
     * Direct FK to the aircraft-model {@link Seat} template. A cancelled
     * booking still points at its original seat (audit trail) even though
     * the corresponding {@code FlightSeat} row is deleted on cancel.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "seat_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_bookings_seat"))
    private Seat seat;

    /**
     * Per-leg price locked in at reserve time by the pricing chain.
     * The parent {@link Itinerary#getFinalPrice()} is the sum of
     * these, cached so payment / DTO reads don't need to re-add.
     */
    @Column(name = "final_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal finalPrice;
}
