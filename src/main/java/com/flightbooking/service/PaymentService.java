package com.flightbooking.service;

import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Payment;
import com.flightbooking.domain.enums.PaymentMethod;
import com.flightbooking.domain.enums.PaymentStatus;
import com.flightbooking.domain.enums.PaymentType;
import com.flightbooking.exception.PaymentFailedException;
import com.flightbooking.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Stubbed payment gateway. In production this would call Stripe /
 * Adyen / etc. Kept in-process for now — the persistence contract
 * ({@link Payment} rows) is real so the rest of the system doesn't
 * need to change when the real gateway is wired in.
 *
 * <p>Payment is per-<em>itinerary</em>, not per-leg. A multi-leg
 * trip has one CHARGE and one REFUND, both covering the aggregated
 * price. This mirrors how airlines settle a PNR — the traveller
 * pays once and is refunded once, even for a multi-segment ticket.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    /**
     * Charge an itinerary. Callers must pass:
     * <ul>
     *   <li>{@code itinerary} — the already-created {@link Itinerary}
     *       row this charge belongs to. Passed as the entity itself
     *       (rather than a raw id) so the resulting {@link Payment}
     *       row can carry a real {@code @ManyToOne} FK — Hibernate
     *       emits the {@code fk_payments_itinerary} constraint at
     *       DDL time, and an orphaned charge referencing a
     *       non-existent itinerary is rejected by the DB, not just
     *       by convention.</li>
     *   <li>{@code amount} — the aggregated price for the whole
     *       itinerary (sum of every leg's {@code finalPrice}).</li>
     *   <li>{@code idempotencyKey} — the caller's session-wide
     *       {@code X-Idempotency-Key}. A retried charge with the
     *       same key returns the existing row instead of
     *       re-charging.</li>
     * </ul>
     *
     * <p>In production this method would forward {@code idempotencyKey}
     * as the {@code Idempotency-Key} HTTP header on the Stripe /
     * charges / payment_intents call, so at-most-once charging is
     * guaranteed <em>end-to-end</em> — even a network blip between
     * "gateway charged" and "we persisted the Payment row" is safe:
     * the same key on retry deduplicates on the gateway side too.</p>
     */
    @Transactional
    public Payment charge(Itinerary itinerary, BigDecimal amount, PaymentMethod method, String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("Payment idempotency hit for key={} itineraryId={} paymentId={}",
                            idempotencyKey, itinerary.getId(), existing.getId());
                    return existing;
                })
                .orElseGet(() -> {
                    String txnId = "txn_" + UUID.randomUUID();
                    log.info("Charging itineraryId={} amount={} method={} idem={} -> txn={}",
                            itinerary.getId(), amount, method, idempotencyKey, txnId);

                    Payment payment = Payment.builder()
                            .itinerary(itinerary)
                            .idempotencyKey(idempotencyKey)
                            .transactionId(txnId)
                            .type(PaymentType.CHARGE)
                            .status(PaymentStatus.SUCCESS)
                            .amount(amount)
                            .paymentMethod(method)
                            .createdAt(Instant.now())
                            .build();
                    return paymentRepository.save(payment);
                });
    }

    /**
     * Refund a prior charge. Callers must pass:
     * <ul>
     *   <li>{@code originalPaymentId} — the CHARGE row this refund
     *       corresponds to. Amount and payment method are copied
     *       from it so the refund is always for exactly what was
     *       taken.</li>
     *   <li>{@code idempotencyKey} — the caller's refund-scoped
     *       idempotency key (typically {@code "refund:" + cancelKey}
     *       from {@link BookingService#cancel}). Dedupes on the
     *       {@code payments.idempotency_key} unique index — a
     *       retried cancel with the same cancel key returns the
     *       existing REFUND row instead of firing a second gateway
     *       call.</li>
     * </ul>
     *
     * <p>In production this key would be forwarded to the gateway
     * as its {@code Idempotency-Key} HTTP header, guaranteeing
     * at-most-once refund end-to-end — even a network blip after
     * "gateway refunded" but before "we persisted the REFUND row"
     * is safe: the same key on retry deduplicates on the gateway
     * side too.</p>
     */
    @Transactional
    public Payment refund(Long originalPaymentId, String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("Refund idempotency hit for key={} originalPaymentId={} refundId={}",
                            idempotencyKey, originalPaymentId, existing.getId());
                    return existing;
                })
                .orElseGet(() -> {
                    Payment original = paymentRepository.findById(originalPaymentId)
                            .orElseThrow(() -> new PaymentFailedException(
                                    "Original payment not found: " + originalPaymentId));

                    String txnId = "rfnd_" + UUID.randomUUID();
                    log.info("Refunding paymentId={} amount={} idem={} -> txn={}",
                            originalPaymentId, original.getAmount(), idempotencyKey, txnId);

                    Payment refund = Payment.builder()
                            .itinerary(original.getItinerary())
                            .idempotencyKey(idempotencyKey)
                            .transactionId(txnId)
                            .type(PaymentType.REFUND)
                            .status(PaymentStatus.SUCCESS)
                            .amount(original.getAmount())
                            .paymentMethod(original.getPaymentMethod())
                            .createdAt(Instant.now())
                            .build();
                    return paymentRepository.save(refund);
                });
    }
}
