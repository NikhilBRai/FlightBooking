package com.flightbooking.repository;

import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Payment;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.enums.BookingStatus;
import com.flightbooking.domain.enums.PaymentType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
class PaymentRepositoryTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired EntityManager em;

    private RepoFixtures fix;
    private Itinerary itinerary;

    @BeforeEach
    void setUp() {
        fix = new RepoFixtures(em);
        User alice = fix.user("Alice", "a@e");
        FlightModel model = fix.flightModel("Boeing", 6);
        // No legs needed for these tests — they only exercise the
        // payments table and its FK to itineraries.
        itinerary = fix.itinerary(alice, BookingStatus.RESERVED, "k-1", new BigDecimal("1000"));
    }

    @Test
    void findByIdempotencyKey_returnsExactRow() {
        Payment charge = fix.payment(itinerary, PaymentType.CHARGE, "idem-1", "txn_1");

        Optional<Payment> found = paymentRepository.findByIdempotencyKey("idem-1");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(charge.getId());
    }

    @Test
    void findByIdempotencyKey_unknownKeyReturnsEmpty() {
        assertThat(paymentRepository.findByIdempotencyKey("nope")).isEmpty();
    }

    @Test
    void idempotencyKey_uniqueConstraintPreventsDoubleCharge() {
        fix.payment(itinerary, PaymentType.CHARGE, "dup", "txn_a");
        Payment dup = Payment.builder()
                .itinerary(itinerary).type(PaymentType.CHARGE)
                .status(com.flightbooking.domain.enums.PaymentStatus.SUCCESS)
                .idempotencyKey("dup").transactionId("txn_b")
                .amount(new BigDecimal("500")).paymentMethod("card")
                .createdAt(Instant.now()).build();

        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> {
            em.persist(dup);
            em.flush();
        });
        assertThat(t)
                .as("second charge with same idempotency_key must trip the unique index")
                .isNotNull();
    }
}
