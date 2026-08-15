package com.flightbooking.domain.enums;

/**
 * <p>Values here are derived at read time, not stored. In the sparse-inventory
 * model there is no {@code status} column on {@code FlightSeat}:</p>
 * <ul>
 *   <li>{@link #BOOKED} — a {@code FlightSeat} row exists for (flight, seat).</li>
 *   <li>{@link #LOCKED} — a Redis key {@code seat:{fid}:{sid}} exists.</li>
 *   <li>{@link #AVAILABLE} — neither of the above.</li>
 * </ul>
 */
public enum SeatStatus {
    AVAILABLE,
    LOCKED,
    BOOKED
}
