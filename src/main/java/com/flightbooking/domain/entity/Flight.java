package com.flightbooking.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "flights", indexes = {
        // Strict feeder query (deepest backward hop): source=userSource, destination=hub, endTime range.
        @Index(name = "idx_flights_route_end", columnList = "source,destination,end_time"),
        // Inbound spine query: destination=userDest, startTime range.
        @Index(name = "idx_flights_dest_start", columnList = "destination,start_time"),
        // Intermediate-hop landing query: destination=hub, endTime range (source unknown).
        @Index(name = "idx_flights_dest_end", columnList = "destination,end_time"),
        // Batched-strategy pool query (findAllLandingInWindow): endTime range with NO
        // destination filter, so the destination-prefixed indexes above don't help.
        // Solo leading column on end_time backs it.
        @Index(name = "idx_flights_end_time", columnList = "end_time")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "flight_model_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_flights_flight_model"))
    private FlightModel flightModel;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private String destination;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cost;

    /**
     * Denormalized fast-path used by search endpoints so we don't have to
     * count seats on every list query. Kept in sync by the booking service.
     */
    @Column(name = "fully_booked", nullable = false)
    private boolean fullyBooked;

    public Duration getTimeTaken() {
        return Duration.between(startTime, endTime);
    }
}
