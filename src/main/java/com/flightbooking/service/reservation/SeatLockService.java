package com.flightbooking.service.reservation;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

/**
 * Distributed, single-writer lock on a {@code (flightId, seatId)} pair.
 * The lock is the <em>only</em> thing that prevents two callers from
 * writing conflicting {@code bookings} rows for the same seat during the
 * reserve → confirm window; everything else in the flow (the {@code Booking}
 * row itself, the {@code Payment} row, the {@code flight_seats} row) is
 * durable and looked up by primary key.
 *
 * <p>The lock <em>value</em> is opaque to this service — {@code BookingService}
 * always passes the caller-supplied {@code idempotencyKey} as the value.
 * That gives us three properties for free:</p>
 *
 * <ul>
 *   <li><b>Exclusion at reserve</b> — {@link #tryLock} is atomic {@code SET NX EX}
 *       under the hood, so only one concurrent reserve wins the seat.</li>
 *   <li><b>Owner check at confirm</b> — {@link #isHeldBy} lets the confirm
 *       path prove the lock is still <em>ours</em> before it touches
 *       payment / flight_seats. If it isn't, the reservation has expired
 *       or been recycled by someone else and we must refuse cleanly, before
 *       charging anything.</li>
 *   <li><b>Compare-and-delete</b> — {@link #release} deletes only when the
 *       stored value matches, so a late release from an expired holder
 *       can't wipe a live holder's key.</li>
 * </ul>
 *
 * <p>The lock TTL is configured by {@code app.reservation.ttl-minutes} and
 * bounds how long a reserved seat is held out of the pool if the client
 * walks away between reserve and confirm.</p>
 */
public interface SeatLockService {

    /**
     * Try to acquire an exclusive lock on {@code (flightId, seatId)} with
     * {@code lockValue} as the owner tag. Returns {@code true} iff the
     * lock was newly acquired (i.e. no one else was holding it). Never
     * blocks: a losing caller gets an immediate {@code false} and should
     * fail the reserve with a "seat unavailable" error.
     */
    boolean tryLock(long flightId, long seatId, String lockValue, Duration ttl);

    /**
     * True iff a lock exists on {@code (flightId, seatId)} AND its stored
     * value equals {@code expected}. Used by confirm to prove the caller
     * still owns the reservation before charging.
     */
    boolean isHeldBy(long flightId, long seatId, String expected);

    /**
     * Compare-and-delete: releases the lock only if the currently stored
     * value equals {@code expected}. Safe to invoke when the lock has
     * already expired (no-op) or has been recycled to a different holder
     * (also a no-op — the newer holder's key survives).
     */
    void release(long flightId, long seatId, String expected);

    /**
     * Bulk lookup for the seat-map: given a flight and a set of seat ids,
     * returns the subset that currently has an active lock. Powers the
     * {@code LOCKED} pill in {@code GET /flight/{id}}. Contents of the
     * lock (i.e. the value) are not exposed — only presence.
     */
    Set<Long> getLockedSeatIds(long flightId, Collection<Long> candidateSeatIds);
}
