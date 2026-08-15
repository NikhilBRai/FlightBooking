package com.flightbooking.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the schema-level guarantees the application relies on for
 * data integrity. Every mapping listed here corresponds to a
 * {@code @JoinColumn(foreignKey = @ForeignKey(name = "..."))} on an
 * entity — a refactor that accidentally drops the association (going
 * back to a raw {@code Long} FK, say) would silently regress this
 * test, so we assert on names not just counts.
 *
 * <p>Runs on H2 through {@code @DataJpaTest} so it stays fast
 * (no Spring web context) and doesn't require Postgres to be running.
 * Uses H2's {@code INFORMATION_SCHEMA} directly — portable enough
 * that swapping to a JDBC-standard reflection call would be a minor
 * change if we ever push the FK sweep into a Postgres integration
 * test.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase
class SchemaConstraintsTest {

    @Autowired EntityManager em;

    /**
     * Every named FK from {@code @ForeignKey(name=...)} sprinkled across
     * the entity classes. If you add a new named FK, add it here too —
     * a missing entry means it's silently absent from the DDL.
     */
    private static final List<String> EXPECTED_FKS = List.of(
            "FK_ITINERARIES_USER",
            "FK_ITINERARIES_PAYMENT",
            "FK_BOOKINGS_ITINERARY",
            "FK_BOOKINGS_FLIGHT",
            "FK_BOOKINGS_SEAT",
            "FK_PAYMENTS_ITINERARY",
            "FK_FLIGHT_SEATS_FLIGHT",
            "FK_FLIGHT_SEATS_SEAT",
            "FK_WAITLIST_FLIGHT",
            "FK_WAITLIST_USER",
            "FK_SEATS_FLIGHT_MODEL",
            "FK_FLIGHTS_FLIGHT_MODEL"
    );

    @Test
    void namedForeignKeysAreEmittedByHibernateDdl() {
        // H2 exposes FKs through INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS,
        // not TABLE_CONSTRAINTS — the latter only lists PK/UNIQUE/CHECK.
        @SuppressWarnings("unchecked")
        List<String> actual = em.createNativeQuery(
                        "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC'")
                .getResultList();

        // H2 stores identifiers upper-cased; normalise defensively so a
        // future dialect switch (e.g. mixed-case Postgres names) doesn't
        // fail on a red herring.
        List<String> upper = actual.stream().map(String::toUpperCase).toList();
        assertThat(upper).containsAll(EXPECTED_FKS);
    }

    @Test
    void itinerariesPaymentIdIsNullableAtTheColumnLevel() {
        // itineraries.payment_id is only set on the RESERVED ->
        // CONFIRMED transition, so the column MUST be nullable — a
        // schema drift that makes it NOT NULL would prevent every
        // reserve from ever succeeding.
        String isNullable = (String) em.createNativeQuery(
                        "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME = 'ITINERARIES' AND COLUMN_NAME = 'PAYMENT_ID'")
                .getSingleResult();
        assertThat(isNullable).isEqualToIgnoringCase("YES");
    }

    @Test
    void paymentsItineraryIdIsNotNull() {
        // Inverse of the above: every payment MUST belong to an
        // itinerary. Enforced by @JoinColumn(nullable=false) on the
        // Payment.itinerary association.
        String isNullable = (String) em.createNativeQuery(
                        "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME = 'PAYMENTS' AND COLUMN_NAME = 'ITINERARY_ID'")
                .getSingleResult();
        assertThat(isNullable).isEqualToIgnoringCase("NO");
    }

    @Test
    void bookingsItineraryIdIsNotNull() {
        // Every leg belongs to exactly one itinerary — a "floating"
        // booking without a parent itinerary would break the whole
        // idempotency and cancel-cascade story.
        String isNullable = (String) em.createNativeQuery(
                        "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME = 'BOOKINGS' AND COLUMN_NAME = 'ITINERARY_ID'")
                .getSingleResult();
        assertThat(isNullable).isEqualToIgnoringCase("NO");
    }
}
