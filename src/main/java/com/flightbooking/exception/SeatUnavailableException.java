package com.flightbooking.exception;

/** Raised when a seat can't be reserved because it's already LOCKED or BOOKED. */
public class SeatUnavailableException extends RuntimeException {
    public SeatUnavailableException(String message) {
        super(message);
    }
}
