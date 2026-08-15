package com.flightbooking.service.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-JVM in-memory {@link SeatLockService}, gated on
 * {@code app.seat-lock.backend=inmemory} (default). Backed by a single
 * {@link ConcurrentHashMap} keyed by {@code seat:{flightId}:{seatId}};
 * entries age out lazily on read — no background sweeper.
 *
 * <p>Intended for local dev / tests only. In a multi-node deployment this
 * will not guard sibling JVMs and MUST be swapped for the Redis backend by
 * setting {@code app.seat-lock.backend=redis}.</p>
 */
@Service
@ConditionalOnProperty(name = "app.seat-lock.backend", havingValue = "inmemory", matchIfMissing = true)
public class InMemorySeatLockService implements SeatLockService {

    private record Entry(String value, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return expiresAt.isBefore(now);
        }
    }

    private final ConcurrentHashMap<String, Entry> bySeatKey = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(long flightId, long seatId, String lockValue, Duration ttl) {
        Instant now = Instant.now();
        Entry candidate = new Entry(lockValue, now.plus(ttl));
        // Atomic put-if-absent-or-expired: if the slot is empty OR the
        // existing entry has aged out, install ours; otherwise leave the
        // live holder alone. Reference equality on the returned Entry
        // tells us whether we won the slot.
        Entry stored = bySeatKey.compute(seatKey(flightId, seatId), (k, existing) ->
                (existing == null || existing.isExpired(now)) ? candidate : existing);
        return stored == candidate;
    }

    @Override
    public boolean isHeldBy(long flightId, long seatId, String expected) {
        Instant now = Instant.now();
        Entry entry = bySeatKey.get(seatKey(flightId, seatId));
        if (entry == null || entry.isExpired(now)) return false;
        return Objects.equals(entry.value(), expected);
    }

    @Override
    public void release(long flightId, long seatId, String expected) {
        String key = seatKey(flightId, seatId);
        // Compare-and-delete: only drop the entry if it's still ours.
        // The two-arg overload compares by Entry.equals(), so we can't
        // use it here (we don't know the exact expiry). Use compute()
        // and match on value only.
        bySeatKey.computeIfPresent(key, (k, existing) ->
                Objects.equals(existing.value(), expected) ? null : existing);
    }

    @Override
    public Set<Long> getLockedSeatIds(long flightId, Collection<Long> candidateSeatIds) {
        Instant now = Instant.now();
        Set<Long> held = new HashSet<>();
        for (Long seatId : candidateSeatIds) {
            Entry entry = bySeatKey.get(seatKey(flightId, seatId));
            if (entry != null && !entry.isExpired(now)) {
                held.add(seatId);
            }
        }
        return held;
    }

    private static String seatKey(long flightId, long seatId) {
        return "seat:" + flightId + ":" + seatId;
    }
}
