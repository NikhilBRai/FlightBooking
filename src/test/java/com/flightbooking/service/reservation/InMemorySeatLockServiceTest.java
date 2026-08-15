package com.flightbooking.service.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-memory backend is the trust anchor for every unit and
 * integration test in this project (Redis is @ConditionalOnProperty
 * off). Bugs here poison every test above it, so we cover the full
 * contract — happy path, TTL expiry, compare-and-delete on release,
 * and a concurrent-race stress test.
 */
class InMemorySeatLockServiceTest {

    private InMemorySeatLockService svc;

    @BeforeEach
    void setUp() {
        svc = new InMemorySeatLockService();
    }

    @Test
    void firstCallerWinsSlotSecondCallerIsRefused() {
        assertThat(svc.tryLock(1L, 10L, "keyA", Duration.ofMinutes(5))).isTrue();
        assertThat(svc.tryLock(1L, 10L, "keyB", Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void isHeldByReturnsTrueOnlyForTheExactHolder() {
        svc.tryLock(1L, 10L, "keyA", Duration.ofMinutes(5));

        assertThat(svc.isHeldBy(1L, 10L, "keyA")).isTrue();
        assertThat(svc.isHeldBy(1L, 10L, "keyB")).isFalse();
        assertThat(svc.isHeldBy(1L, 99L, "keyA")).isFalse();
    }

    @Test
    void releaseIsCompareAndDelete_wrongHolderCannotEvict() {
        svc.tryLock(1L, 10L, "keyA", Duration.ofMinutes(5));

        svc.release(1L, 10L, "keyB");
        assertThat(svc.isHeldBy(1L, 10L, "keyA")).isTrue();

        svc.release(1L, 10L, "keyA");
        assertThat(svc.isHeldBy(1L, 10L, "keyA")).isFalse();
    }

    @Test
    @DisplayName("expired entry auto-evicts on next tryLock; second caller then wins")
    void expiredEntryIsLazilyEvicted() throws InterruptedException {
        assertThat(svc.tryLock(1L, 10L, "keyA", Duration.ofMillis(50))).isTrue();
        Thread.sleep(120);

        assertThat(svc.tryLock(1L, 10L, "keyB", Duration.ofMinutes(5))).isTrue();
        assertThat(svc.isHeldBy(1L, 10L, "keyB")).isTrue();
        assertThat(svc.isHeldBy(1L, 10L, "keyA")).isFalse();
    }

    @Test
    @DisplayName("getLockedSeatIds returns only currently-held candidate seats")
    void getLockedSeatIdsFiltersToHeldSubset() {
        svc.tryLock(1L, 10L, "a", Duration.ofMinutes(5));
        svc.tryLock(1L, 12L, "b", Duration.ofMinutes(5));

        Set<Long> held = svc.getLockedSeatIds(1L, List.of(10L, 11L, 12L, 13L));

        assertThat(held).containsExactlyInAnyOrder(10L, 12L);
    }

    @Test
    @DisplayName("getLockedSeatIds ignores expired entries")
    void getLockedSeatIdsIgnoresExpired() throws InterruptedException {
        svc.tryLock(1L, 10L, "a", Duration.ofMillis(50));
        svc.tryLock(1L, 11L, "b", Duration.ofMinutes(5));
        Thread.sleep(120);

        Set<Long> held = svc.getLockedSeatIds(1L, List.of(10L, 11L));

        assertThat(held).containsExactly(11L);
    }

    @Test
    @DisplayName("under N-way contention exactly one thread wins the same seat")
    void concurrentTryLock_exactlyOneWinner() throws Exception {
        int contenders = 32;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();

            List<Future<Boolean>> results = IntStream.range(0, contenders)
                    .mapToObj(i -> pool.submit(() -> {
                        gate.await();
                        boolean won = svc.tryLock(42L, 7L,
                                "k-" + i, Duration.ofMinutes(5));
                        if (won) winners.incrementAndGet();
                        return won;
                    }))
                    .collect(Collectors.toList());

            gate.countDown();
            for (Future<Boolean> f : results) f.get(2, TimeUnit.SECONDS);

            assertThat(winners.get())
                    .as("exactly one caller must acquire the seat lock")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("release then tryLock allows the same seat to be re-acquired")
    void releaseAllowsReAcquisition() {
        svc.tryLock(1L, 10L, "a", Duration.ofMinutes(5));
        svc.release(1L, 10L, "a");

        assertThat(svc.tryLock(1L, 10L, "b", Duration.ofMinutes(5))).isTrue();
        assertThat(svc.isHeldBy(1L, 10L, "b")).isTrue();
    }
}
