package com.flightbooking.domain.entity;

import com.flightbooking.domain.enums.PaymentMethod;
import com.flightbooking.domain.enums.PaymentStatus;
import com.flightbooking.domain.enums.PaymentType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_itinerary", columnList = "itinerary_id"),
        @Index(name = "idx_payments_idem", columnList = "idempotency_key", unique = true)
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning-side association to the {@link Itinerary} this payment
     * belongs to. Non-null — a payment always corresponds to exactly
     * one itinerary (created at reserve time, charged at confirm).
     * One payment covers every leg of a multi-leg itinerary; there's
     * no per-leg payment row.
     *
     * <p>The {@link ForeignKey} is named explicitly so the DDL emits
     * {@code fk_payments_itinerary} in {@code information_schema}
     * (default names are opaque and painful to search for).</p>
     *
     * <p>{@link FetchType#LAZY} so a payments-list read doesn't drag
     * the entire itinerary-and-legs graph into memory. Callers that
     * need the itinerary should navigate explicitly inside a
     * transaction.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "itinerary_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_payments_itinerary"))
    private Itinerary itinerary;

    /** Opaque id returned by the external payment gateway. */
    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId;

    /**
     * Deterministic dedupe key for the charge, derived from the
     * {@code X-Idempotency-Key} the caller sent to
     * {@code POST /itinerary/{id}/confirm} (or {@code "refund:" + key}
     * from the cancel path). A duplicate charge / refund attempt with
     * the same key short-circuits to the existing row in {@code
     * PaymentService}, which is a defence-in-depth complement to the
     * gateway's own {@code Idempotency-Key} header.
     */
    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 16)
    private PaymentMethod paymentMethod;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
