package com.flightbooking.domain.enums;

/**
 * Persisted state of a booking. The row is created at
 * {@code POST /booking/reserve} time in {@link #RESERVED} state and later
 * flipped to {@link #CONFIRMED} (happy path) or {@link #CANCELLED} (user
 * cancels a confirmed booking).
 *
 * <p>The reason we persist even in the not-yet-paid state — despite the
 * cost of extra rows for reservations that never get confirmed — is that
 * it's the only durable anchor we have for the {@code Payment} row that
 * {@code confirm} is about to create. Without a pre-existing
 * {@code bookings.id} to point at, a payment gateway success followed by
 * a DB failure would leave a charged customer with no audit trail.</p>
 *
 * <ul>
 *   <li>{@link #RESERVED} — seat locked (Redis + Booking row), no payment
 *       yet. The corresponding Redis seat-lock TTL bounds how long this
 *       state may live before the row is effectively abandoned (there is
 *       no aggressive sweeper: the row is harmless because no
 *       {@code flight_seats} row exists for the seat yet).</li>
 *   <li>{@link #CONFIRMED} — payment charged, {@code flight_seats} row
 *       written; the seat is taken.</li>
 *   <li>{@link #CANCELLED} — a previously-confirmed booking that has been
 *       cancelled and refunded.</li>
 * </ul>
 */
public enum BookingStatus {
    RESERVED,
    CONFIRMED,
    CANCELLED
}
