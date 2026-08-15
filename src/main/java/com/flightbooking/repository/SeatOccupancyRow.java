package com.flightbooking.repository;

/**
 * Projection returned by
 * {@link SeatRepository#findSeatOccupancy(Long, Long)} — one row per
 * seat template in an aircraft model, with a nullable pointer to the
 * corresponding {@code flight_seats} row for a specific flight.
 *
 * <p>{@code flightSeatId} being {@code null} means the seat is
 * unbooked on that flight (LEFT JOIN produced no match). Callers use
 * {@link #isBooked()} rather than probing the id directly so the
 * "presence = booked" convention lives in exactly one place.</p>
 *
 * <p>This replaces the old "fetch seat layout, fetch flight_seats,
 * intersect in Java" three-step pattern with a single ordered
 * projection — the DB does the join once and returns seat plus
 * occupancy together.</p>
 */
public record SeatOccupancyRow(Long seatId, String seatNumber, Long flightSeatId) {

    public boolean isBooked() {
        return flightSeatId != null;
    }
}
