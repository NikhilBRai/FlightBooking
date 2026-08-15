package com.flightbooking.repository;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.entity.WaitlistEntry;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
class WaitlistRepositoryTest {

    @Autowired WaitlistRepository waitlistRepository;
    @Autowired EntityManager em;

    private RepoFixtures fix;
    private Flight flightA;
    private Flight flightB;
    private User alice;
    private User bob;
    private User carol;

    @BeforeEach
    void setUp() {
        fix = new RepoFixtures(em);
        alice = fix.user("Alice", "a@e");
        bob = fix.user("Bob", "b@e");
        carol = fix.user("Carol", "c@e");
        FlightModel model = fix.flightModel("Boeing", 6);
        flightA = fix.flight(model, "BLR", "BOM",
                Instant.parse("2030-01-01T08:00:00Z"), Duration.ofHours(2), new BigDecimal("3200"));
        flightB = fix.flight(model, "BLR", "HYD",
                Instant.parse("2030-01-01T09:00:00Z"), Duration.ofMinutes(90), new BigDecimal("2200"));
    }

    @Test
    void findByFlight_ordered_returnsFifoByAddedAt() {
        fix.waitlist(flightA, carol, Instant.parse("2030-01-01T00:00:02Z"));
        fix.waitlist(flightA, alice, Instant.parse("2030-01-01T00:00:00Z"));
        fix.waitlist(flightA, bob,   Instant.parse("2030-01-01T00:00:01Z"));

        List<WaitlistEntry> ordered = waitlistRepository.findByFlight_IdOrderByAddedAtAsc(flightA.getId());

        assertThat(ordered).extracting(w -> w.getUser().getName())
                .containsExactly("Alice", "Bob", "Carol");
    }

    @Test
    void findByFlight_ordered_joinFetchesUser_soFanOutIsNotN_plus_1() {
        fix.waitlist(flightA, alice, Instant.parse("2030-01-01T00:00:00Z"));
        fix.waitlist(flightA, bob,   Instant.parse("2030-01-01T00:00:01Z"));
        fix.waitlist(flightA, carol, Instant.parse("2030-01-01T00:00:02Z"));
        // Clearing the session forces the assertion below to reflect
        // what actually came off the wire — without a JOIN FETCH,
        // Hibernate.isInitialized(user) would be false and the fan-out
        // loop in WaitlistService.notifyAllWaitersOfOpening would then
        // fire one extra SELECT per waiter.
        em.clear();

        List<WaitlistEntry> waiters =
                waitlistRepository.findByFlight_IdOrderByAddedAtAsc(flightA.getId());

        assertThat(waiters).hasSize(3);
        for (WaitlistEntry w : waiters) {
            assertThat(Hibernate.isInitialized(w.getUser()))
                    .as("waiter %s should have user eagerly loaded", w.getId())
                    .isTrue();
        }
    }

    @Test
    void findByFlight_ordered_scopedToFlight() {
        fix.waitlist(flightA, alice, Instant.now());
        fix.waitlist(flightB, bob,   Instant.now());
        assertThat(waitlistRepository.findByFlight_IdOrderByAddedAtAsc(flightA.getId())).hasSize(1);
        assertThat(waitlistRepository.findByFlight_IdOrderByAddedAtAsc(flightB.getId())).hasSize(1);
    }

    @Test
    void findByFlight_user_returnsMatchingRow() {
        WaitlistEntry saved = fix.waitlist(flightA, alice, Instant.parse("2030-01-01T00:00:00Z"));
        Optional<WaitlistEntry> found = waitlistRepository.findByFlight_IdAndUser_Id(flightA.getId(), alice.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    void existsByFlight_user_true_only_when_row_present() {
        fix.waitlist(flightA, alice, Instant.now());
        assertThat(waitlistRepository.existsByFlight_IdAndUser_Id(flightA.getId(), alice.getId())).isTrue();
        assertThat(waitlistRepository.existsByFlight_IdAndUser_Id(flightA.getId(), bob.getId())).isFalse();
        assertThat(waitlistRepository.existsByFlight_IdAndUser_Id(flightB.getId(), alice.getId())).isFalse();
    }

    @Test
    void deleteByFlight_user_removesExactlyOneRow_returnsCount() {
        fix.waitlist(flightA, alice, Instant.now());
        fix.waitlist(flightA, bob, Instant.now());

        long removed = waitlistRepository.deleteByFlight_IdAndUser_Id(flightA.getId(), alice.getId());
        em.flush();
        em.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(waitlistRepository.existsByFlight_IdAndUser_Id(flightA.getId(), alice.getId())).isFalse();
        assertThat(waitlistRepository.existsByFlight_IdAndUser_Id(flightA.getId(), bob.getId())).isTrue();
    }

    @Test
    void deleteByFlight_user_isIdempotent_returnsZeroWhenNoRow() {
        long removed = waitlistRepository.deleteByFlight_IdAndUser_Id(flightA.getId(), alice.getId());
        assertThat(removed).isZero();
    }

    @Test
    void unique_flight_user_constraint_preventsDoubleJoin() {
        fix.waitlist(flightA, alice, Instant.now());
        WaitlistEntry dup = WaitlistEntry.builder()
                .flight(flightA).user(alice).addedAt(Instant.now()).build();
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> {
            em.persist(dup);
            em.flush();
        });
        assertThat(t).isNotNull();
    }
}
