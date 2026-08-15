package com.flightbooking.service;

import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Payment;
import com.flightbooking.domain.enums.PaymentStatus;
import com.flightbooking.domain.enums.PaymentType;
import com.flightbooking.exception.PaymentFailedException;
import com.flightbooking.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service is a stub for a real gateway; what we can (and must)
 * verify is its persistence contract and idempotency semantics — these
 * are what closes the "gateway ok but we lost the payment row" gap.
 * Every branch of charge() and refund() is covered so a regression
 * that stops honouring the {@code X-Idempotency-Key} header fails
 * loudly instead of silently double-charging.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @InjectMocks PaymentService svc;

    // ---- charge -------------------------------------------------------

    private static Itinerary itineraryRef(long id) {
        return Itinerary.builder().id(id).build();
    }

    @Test
    void charge_persistsSuccessRowWhenNoPriorAttempt() {
        when(paymentRepository.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(99L);
            return p;
        });

        Itinerary itinerary = itineraryRef(42L);
        Payment out = svc.charge(itinerary, new BigDecimal("500.00"), "card", "k1");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment persisted = captor.getValue();

        assertThat(out.getId()).isEqualTo(99L);
        assertThat(persisted.getItinerary()).isSameAs(itinerary);
        assertThat(persisted.getIdempotencyKey()).isEqualTo("k1");
        assertThat(persisted.getType()).isEqualTo(PaymentType.CHARGE);
        assertThat(persisted.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(persisted.getAmount()).isEqualByComparingTo("500.00");
        assertThat(persisted.getPaymentMethod()).isEqualTo("card");
        assertThat(persisted.getTransactionId()).startsWith("txn_");
        assertThat(persisted.getCreatedAt()).isNotNull();
    }

    @Test
    void charge_isIdempotentOnDuplicateKey_returnsExistingWithoutInsert() {
        Payment existing = Payment.builder().id(7L).idempotencyKey("k1")
                .type(PaymentType.CHARGE).status(PaymentStatus.SUCCESS)
                .amount(new BigDecimal("500")).paymentMethod("card")
                .itinerary(itineraryRef(42L)).transactionId("txn_pre").build();
        when(paymentRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(existing));

        Payment out = svc.charge(itineraryRef(42L), new BigDecimal("500.00"), "card", "k1");

        assertThat(out).isSameAs(existing);
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void charge_transactionIdIsFreshPerCall() {
        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment p1 = svc.charge(itineraryRef(1L), BigDecimal.TEN, "m", "k-a");
        Payment p2 = svc.charge(itineraryRef(1L), BigDecimal.TEN, "m", "k-b");

        assertThat(p1.getTransactionId()).isNotEqualTo(p2.getTransactionId());
    }

    // ---- refund -------------------------------------------------------

    @Test
    void refund_persistsRefundRowCopyingAmountAndMethodFromOriginal() {
        Itinerary itinerary = itineraryRef(42L);
        Payment original = Payment.builder().id(11L).itinerary(itinerary)
                .type(PaymentType.CHARGE).status(PaymentStatus.SUCCESS)
                .amount(new BigDecimal("500.00")).paymentMethod("upi")
                .idempotencyKey("charge-key").transactionId("txn_orig").build();
        when(paymentRepository.findByIdempotencyKey("refund:cancel-1")).thenReturn(Optional.empty());
        when(paymentRepository.findById(11L)).thenReturn(Optional.of(original));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(88L);
            return p;
        });

        Payment out = svc.refund(11L, "refund:cancel-1");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        Payment refund = captor.getValue();

        assertThat(out.getId()).isEqualTo(88L);
        assertThat(refund.getType()).isEqualTo(PaymentType.REFUND);
        assertThat(refund.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(refund.getAmount()).isEqualByComparingTo("500.00");
        assertThat(refund.getPaymentMethod()).isEqualTo("upi");
        assertThat(refund.getItinerary()).isSameAs(itinerary);
        assertThat(refund.getIdempotencyKey()).isEqualTo("refund:cancel-1");
        assertThat(refund.getTransactionId()).startsWith("rfnd_");
    }

    @Test
    void refund_isIdempotentOnDuplicateKey_returnsExistingRefund() {
        Payment existingRefund = Payment.builder().id(88L).itinerary(itineraryRef(42L))
                .type(PaymentType.REFUND).idempotencyKey("refund:c-1")
                .status(PaymentStatus.SUCCESS).amount(new BigDecimal("500"))
                .paymentMethod("card").transactionId("rfnd_pre").build();
        when(paymentRepository.findByIdempotencyKey("refund:c-1")).thenReturn(Optional.of(existingRefund));

        Payment out = svc.refund(11L, "refund:c-1");

        assertThat(out).isSameAs(existingRefund);
        verify(paymentRepository, never()).findById(any());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void refund_throwsWhenOriginalPaymentIdIsUnknown() {
        when(paymentRepository.findByIdempotencyKey("refund:missing")).thenReturn(Optional.empty());
        when(paymentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(PaymentFailedException.class)
                .isThrownBy(() -> svc.refund(999L, "refund:missing"))
                .withMessageContaining("999");
        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
