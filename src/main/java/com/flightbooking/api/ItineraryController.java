package com.flightbooking.api;

import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
import com.flightbooking.api.dto.ReserveRequest;
import com.flightbooking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Itinerary lifecycle endpoints. An itinerary is the top-level unit
 * a caller reserves, confirms, and cancels — a direct flight is a
 * single-leg itinerary, a two-hop trip is two-leg, and so on. Every
 * mutation is all-or-nothing across every leg.
 */
@RestController
@RequestMapping("/itinerary")
@RequiredArgsConstructor
public class ItineraryController {

    /**
     * Header carrying the caller's user id. In a real deployment
     * this would be a bearer JWT that an auth filter validates and
     * unpacks into a {@code Principal}; for this project we skip
     * the auth layer and read the id straight off the request.
     * Kept as a constant so tests and any future filter reference
     * the same string.
     */
    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * Client-generated key that scopes an entire reserve → confirm
     * session <em>for the whole itinerary</em>. One key covers
     * every leg. Same key on both endpoints. Callers should generate
     * a fresh key per session (typically a UUID minted before the
     * reserve request), keep it stable across in-session retries,
     * and rotate it if they truly want a second, independent
     * booking.
     *
     * <p>Server-side we use it as:</p>
     * <ul>
     *   <li>The unique key on
     *       {@code itineraries.idempotency_key} — a duplicate
     *       reserve returns the existing itineraryId.</li>
     *   <li>The owner-tag on every leg's Redis seat lock —
     *       confirm proves each lock is still ours before
     *       charging.</li>
     *   <li>The Stripe-style {@code Idempotency-Key} forwarded
     *       through {@link com.flightbooking.service.PaymentService#charge}
     *       to the payment gateway.</li>
     * </ul>
     */
    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

    private final BookingService bookingService;

    /**
     * {@code POST /itinerary/reserve} — soft-lock every seat in the
     * requested itinerary and persist one {@code itineraries} row +
     * N {@code bookings} legs in {@code RESERVED} state. Response
     * carries the {@code itineraryId} the client must POST back to
     * {@code /itinerary/{itineraryId}/confirm} to pay for it.
     *
     * <p>All-or-nothing: if any leg's seat lock fails, all
     * previously-acquired locks are released and the request is
     * refused with 409. No partial reservation is ever persisted.
     * Retrying with the same {@code X-Idempotency-Key} returns the
     * same itineraryId — no duplicate rows, no duplicate locks.</p>
     */
    @PostMapping("/reserve")
    public BookingItineraryDto reserve(
            @RequestHeader(USER_ID_HEADER) Long userId,
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ReserveRequest request) {
        return bookingService.reserve(userId, idempotencyKey, request);
    }

    /**
     * {@code POST /itinerary/{itineraryId}/confirm} — promote a
     * {@code RESERVED} itinerary to {@code CONFIRMED}: charge one
     * payment covering every leg, INSERT one {@code flight_seats}
     * row per leg, release every Redis seat lock. Idempotent:
     * retrying with the same {@code X-Idempotency-Key} returns the
     * same {@link BookingItineraryDto} without re-charging.
     *
     * <p>Error responses:</p>
     * <ul>
     *   <li>{@code 404} — no itinerary with that id.</li>
     *   <li>{@code 409} — caller isn't the itinerary's owner, or
     *       the idempotency key doesn't match what was stamped at
     *       reserve time, or any leg's Redis seat lock has expired /
     *       been recycled (client should reserve the whole
     *       itinerary again), or the itinerary has been
     *       cancelled.</li>
     * </ul>
     */
    @PostMapping("/{itineraryId}/confirm")
    public BookingItineraryDto confirm(@PathVariable Long itineraryId,
                                       @RequestHeader(USER_ID_HEADER) Long userId,
                                       @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                       @Valid @RequestBody ConfirmRequest request) {
        return bookingService.confirm(itineraryId, userId, idempotencyKey, request);
    }

    /**
     * {@code GET /itinerary/{itineraryId}} — view the full itinerary + legs.
     *
     * <p>Requires the same {@code X-User-Id} header as the mutating
     * endpoints and enforces the same ownership rule: callers can
     * only view their own itineraries. A mismatched or missing user
     * yields the same masked {@code 409 "Reservation not found for
     * this user"} we return on confirm/cancel, so an attacker
     * poking at random itinerary IDs can't distinguish "not yours"
     * from "doesn't exist".</p>
     */
    @GetMapping("/{itineraryId}")
    public BookingItineraryDto view(@PathVariable Long itineraryId,
                                    @RequestHeader(USER_ID_HEADER) Long userId) {
        return bookingService.getItinerary(itineraryId, userId);
    }

    /**
     * {@code POST /itinerary/{itineraryId}/cancel} — cancel a
     * CONFIRMED itinerary: refund + release every leg + per-flight
     * waitlist fan-out. RESERVED itineraries are refused (their
     * seat locks expire on their own after the TTL). Only the
     * itinerary's owner may cancel; calling with a different
     * {@code X-User-Id} returns {@code 409} with a generic "not
     * found for this user" message so a caller poking at random
     * itineraryIds can't distinguish "wrong user" from "no such
     * itinerary".
     *
     * <p>The client must mint a fresh {@code X-Idempotency-Key}
     * for every cancel session (distinct from the reserve/confirm
     * key). Retrying the same cancel with the same key returns the
     * cached CANCELLED {@link BookingItineraryDto} without
     * re-refunding, re-notifying, or re-promoting waitlist
     * entries. Calling cancel a second time with a
     * <em>different</em> key on an already-CANCELLED itinerary
     * returns {@code 409}.</p>
     */
    @PostMapping("/{itineraryId}/cancel")
    public BookingItineraryDto cancel(@PathVariable Long itineraryId,
                                      @RequestHeader(USER_ID_HEADER) Long userId,
                                      @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey) {
        return bookingService.cancel(itineraryId, userId, idempotencyKey);
    }
}
