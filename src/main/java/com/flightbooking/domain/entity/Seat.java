package com.flightbooking.domain.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A seat template for a FlightModel (e.g. seat "12A" on a Boeing 737-800).
 * Per-flight status is stored on {@link FlightSeat}.
 */
@Entity
@Table(
        name = "seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"flight_model_id", "seat_number"})
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "flight_model_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_seats_flight_model"))
    private FlightModel flightModel;
}
