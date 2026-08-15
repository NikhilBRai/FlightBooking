package com.flightbooking.exception;

/** Raised when a booking is not in a state where the requested operation is allowed. */
public class InvalidBookingStateException extends RuntimeException {
    public InvalidBookingStateException(String message) {
        super(message);
    }
}
