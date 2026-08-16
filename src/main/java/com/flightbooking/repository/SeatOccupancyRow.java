package com.flightbooking.repository;

/**
 * Projection returned by
 * {@link SeatRepository#findSeatOccupancy(Long, Long)} and
 * {@link SeatRepository#findSeatOccupancyForFlights(java.util.Collection)}.
 * One row per seat template in an aircraft model, with a nullable
 * pointer to the corresponding {@code flight_seats} row for a specific
 * flight.
 *
 * <p>{@code flightSeatId} being {@code null} means the seat is unbooked
 * on that flight (LEFT JOIN produced no match). Callers use
 * {@link #isBooked()} rather than probing the id directly so the
 * "presence = booked" convention lives in exactly one place.</p>
 *
 * <p>{@code flightId} is populated only by the bulk multi-flight query
 * (needed by callers that group rows by flight). The single-flight
 * query leaves it {@code null} since the caller already knows which
 * flight it asked about — see the legacy 3-arg constructor below.</p>
 *
 * <p>This replaces the old "fetch seat layout, fetch flight_seats,
 * intersect in Java" three-step pattern with a single ordered
 * projection — the DB does the join once and returns seat plus
 * occupancy together.</p>
 */
public record SeatOccupancyRow(Long seatId, String seatNumber, Long flightSeatId, Long flightId) {

    /**
     * Legacy 3-arg constructor used by
     * {@link SeatRepository#findSeatOccupancy(Long, Long)} and by
     * tests that only care about the single-flight shape. Leaves
     * {@link #flightId()} as {@code null} because the caller already
     * knows the flight from context.
     */
    public SeatOccupancyRow(Long seatId, String seatNumber, Long flightSeatId) {
        this(seatId, seatNumber, flightSeatId, null);
    }

    public boolean isBooked() {
        return flightSeatId != null;
    }
}
