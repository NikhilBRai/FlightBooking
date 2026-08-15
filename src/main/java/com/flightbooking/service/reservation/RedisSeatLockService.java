package com.flightbooking.service.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed {@link SeatLockService}, gated on
 * {@code app.seat-lock.backend=redis}. All the atomicity we need comes
 * straight from Redis primitives:
 *
 * <ul>
 *   <li>{@link #tryLock} → {@code SET key value NX EX ttl}</li>
 *   <li>{@link #isHeldBy} → {@code GET key} + string compare</li>
 *   <li>{@link #release} → tiny Lua script for atomic compare-and-delete
 *       (the same pattern the official Redis docs recommend for safely
 *       releasing distributed locks)</li>
 *   <li>{@link #getLockedSeatIds} → {@code MGET} across the candidate keys
 *       and treat non-null as "held"</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.seat-lock.backend", havingValue = "redis")
public class RedisSeatLockService implements SeatLockService {

    /**
     * Standard "unlock if still ours" pattern — without this, a caller
     * whose lock has already expired could delete the key while a new
     * holder has just acquired it.
     */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('del', KEYS[1]) " +
                    "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisSeatLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryLock(long flightId, long seatId, String lockValue, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(seatKey(flightId, seatId), lockValue, ttl);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean isHeldBy(long flightId, long seatId, String expected) {
        String actual = redis.opsForValue().get(seatKey(flightId, seatId));
        return actual != null && actual.equals(expected);
    }

    @Override
    public void release(long flightId, long seatId, String expected) {
        redis.execute(RELEASE, Collections.singletonList(seatKey(flightId, seatId)), expected);
    }

    @Override
    public Set<Long> getLockedSeatIds(long flightId, Collection<Long> candidateSeatIds) {
        if (candidateSeatIds.isEmpty()) return Set.of();
        List<Long> ordered = new ArrayList<>(candidateSeatIds);
        List<String> keys = ordered.stream()
                .map(sid -> seatKey(flightId, sid))
                .toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) return Set.of();

        Set<Long> held = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (values.get(i) != null) {
                held.add(ordered.get(i));
            }
        }
        return held;
    }

    private static String seatKey(long flightId, long seatId) {
        return "seat:" + flightId + ":" + seatId;
    }
}
