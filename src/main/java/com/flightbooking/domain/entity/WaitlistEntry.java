package com.flightbooking.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "waitlist",
        uniqueConstraints = @UniqueConstraint(columnNames = {"flight_id", "user_id"}),
        indexes = @Index(name = "idx_waitlist_flight_added", columnList = "flight_id,added_at")
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "flight_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_waitlist_flight"))
    private Flight flight;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_waitlist_user"))
    private User user;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;
}
