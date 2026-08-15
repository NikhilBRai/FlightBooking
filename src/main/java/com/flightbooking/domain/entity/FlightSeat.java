package com.flightbooking.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Represents a <em>booked</em> seat on a specific flight.
 *
 * <p>Sparse-inventory model: the presence of a row here means the seat is
 * confirmed BOOKED. Absence means AVAILABLE. Layout (which seat numbers exist
 * on the aircraft) comes from the {@code seats} table via
 * {@link Flight#getFlightModel()}. Transient LOCKED state lives in Redis.</p>
 *
 * <p>Consequences:</p>
 * <ul>
 *   <li>Flights start empty here — {@code DataSeeder} does not pre-seed rows.</li>
 *   <li>Booking flow: {@code confirm} INSERTs a row; {@code cancel} DELETEs it.</li>
 *   <li>{@code UNIQUE(flight_id, seat_id)} is the last-line defence against
 *       double-booking — even if Redis is bypassed, the DB rejects a second
 *       insert with a {@code DataIntegrityViolationException}.</li>
 * </ul>
 */
@Entity
@Table(
        name = "flight_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"flight_id", "seat_id"}),
        indexes = @Index(name = "idx_flight_seats_flight", columnList = "flight_id")
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FlightSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "flight_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_flight_seats_flight"))
    private Flight flight;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "seat_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_flight_seats_seat"))
    private Seat seat;

    @Column(name = "booked_at", nullable = false)
    private Instant bookedAt;
}
