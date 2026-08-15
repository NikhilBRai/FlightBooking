package com.flightbooking.domain.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * A physical aircraft model (e.g. "Boeing 737-800"). Seat layout is defined per
 * model, not per flight — every Boeing 737-800 has the same seat numbers.
 */
@Entity
@Table(name = "flight_models")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class FlightModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String make;

    @Column(nullable = false)
    private int totalSeats;
}
