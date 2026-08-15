package com.flightbooking.repository;

import com.flightbooking.domain.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Finds a payment by its idempotency key — the caller's
     * {@code X-Idempotency-Key} at confirm, or {@code "refund:" + key}
     * at cancel. A retried charge or refund with the same key
     * short-circuits on this lookup, and the unique index on
     * {@code idempotency_key} is the last-line defence if the check
     * races with a concurrent write.
     */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
