package com.flightbooking.api;

import com.flightbooking.api.dto.ApiError;
import com.flightbooking.exception.InvalidBookingStateException;
import com.flightbooking.exception.PaymentFailedException;
import com.flightbooking.exception.ResourceNotFoundException;
import com.flightbooking.exception.SeatUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(SeatUnavailableException.class)
    public ResponseEntity<ApiError> handleSeatTaken(SeatUnavailableException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidBookingStateException.class)
    public ResponseEntity<ApiError> handleBadState(InvalidBookingStateException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ApiError> handlePaymentFailed(PaymentFailedException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYMENT_REQUIRED, ex.getMessage(), req);
    }

    /**
     * Fires when two concurrent reserves race past the pre-check and
     * both try to insert a {@code bookings.idempotency_key} unique row,
     * or when two concurrent confirms slip past the Redis lock check
     * and both try to insert the {@code flight_seats} UNIQUE row. In
     * both cases the correct client-facing response is 409, not 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT,
                "Conflicting write (seat taken or duplicate request)", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, msg, req);
    }

    /**
     * Missing required header (e.g. {@code X-User-Id} or
     * {@code X-Idempotency-Key} on booking endpoints). Without this,
     * Spring's default surfaces the exception as a 500 via the generic
     * handler below — but a missing client-supplied header is a 400.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST,
                "Missing required request header: " + ex.getHeaderName(), req);
    }

    /**
     * Header/path/query value present but the wrong type (e.g. {@code X-User-Id: abc}
     * when we expect a {@code Long}). Also a client error, not a server crash.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String expected = ex.getRequiredType() == null ? "expected type" : ex.getRequiredType().getSimpleName();
        return build(HttpStatus.BAD_REQUEST,
                "Invalid value for '" + ex.getName() + "': expected " + expected, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    /**
     * Body couldn't be parsed as the target DTO — malformed JSON,
     * unknown enum value ({@code paymentMethod: "monopoly-money"}),
     * type mismatch inside a field, missing body, etc. Every case
     * here is caller-supplied garbage, not a server crash, so 400
     * is the right response. Without this handler the exception
     * falls through to the generic {@link Exception} branch below
     * and surfaces as a misleading 500.
     *
     * <p>We intentionally do NOT echo {@code ex.getMessage()} —
     * Jackson's error message can leak internal field names and
     * partial class paths. A stable, non-leaky message is enough
     * for the client to know they sent bad JSON; the server logs
     * still capture the details.</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST,
                "Malformed request body (invalid JSON or unsupported field value)", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req) {
        ApiError body = ApiError.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(req.getRequestURI())
                .build();
        return ResponseEntity.status(status).body(body);
    }
}
