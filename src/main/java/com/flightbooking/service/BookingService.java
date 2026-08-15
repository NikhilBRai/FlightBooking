package com.flightbooking.service;

import com.flightbooking.api.dto.BookingDto;
import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
import com.flightbooking.api.dto.LegRequest;
import com.flightbooking.api.dto.PriceBreakdownEntry;
import com.flightbooking.api.dto.ReserveRequest;
import com.flightbooking.domain.entity.*;
import com.flightbooking.domain.enums.BookingStatus;
import com.flightbooking.exception.InvalidBookingStateException;
import com.flightbooking.exception.ResourceNotFoundException;
import com.flightbooking.exception.SeatUnavailableException;
import com.flightbooking.repository.BookingRepository;
import com.flightbooking.repository.FlightRepository;
import com.flightbooking.repository.FlightSeatRepository;
import com.flightbooking.repository.ItineraryRepository;
import com.flightbooking.repository.SeatOccupancyRow;
import com.flightbooking.repository.SeatRepository;
import com.flightbooking.repository.UserRepository;
import com.flightbooking.service.pricing.FlightPricingService;
import com.flightbooking.service.pricing.PriceQuote;
import com.flightbooking.service.reservation.SeatLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Itinerary lifecycle service. An itinerary is the atomic unit: a
 * direct flight is a single-leg itinerary, a two-hop trip is
 * two-leg, and so on. Every mutation ({@link #reserve reserve()},
 * {@link #confirm confirm()}, {@link #cancel cancel()}) is
 * all-or-nothing across every leg.
 *
 * <ol>
 *   <li><b>{@link #reserve reserve()}</b> — sorts the caller's
 *       {@code legs} by {@code (flightId, seatId)}, acquires a
 *       Redis seat lock on every leg in that canonical order (so
 *       two callers reserving the same seats in opposite orders
 *       don't both fail on partial locks), then inside one DB
 *       transaction inserts one {@link Itinerary} row and N
 *       {@link Booking} legs. If any lock acquisition fails, the
 *       already-held locks are released in reverse and the request
 *       is refused with a single 409 — no partial reservation is
 *       ever persisted.</li>
 *
 *   <li><b>{@link #confirm confirm()}</b> — proves the caller
 *       still owns every leg's Redis lock, charges <em>one</em>
 *       payment for the aggregated price, INSERTs N
 *       {@code flight_seats} rows, flips the itinerary to
 *       {@code CONFIRMED}. All in one DB transaction — a
 *       mid-flight crash rolls back cleanly and a retry re-runs
 *       the whole thing. Same three defence-in-depth idempotency
 *       layers as the single-leg flow used to have: session key
 *       replay → cached DTO, {@code payments.idempotency_key}
 *       unique index, {@code flight_seats(flight_id, seat_id)}
 *       unique index.</li>
 *
 *   <li><b>{@link #cancel cancel()}</b> — CONFIRMED only. Deletes
 *       every leg's {@code flight_seats} row, refunds the one
 *       payment, flips the itinerary status, fans out waitlist
 *       notifications per affected flight, unflips
 *       {@code fully_booked} on every leg's flight that had it
 *       set.</li>
 * </ol>
 *
 * <p><b>Deadlock-free locking.</b> The seat-lock ordering rule is
 * a global constant: {@code (flightId ASC, seatId ASC)}. Every
 * caller that reserves overlapping legs sorts to the same order,
 * so contention resolves as first-writer-wins on the smallest
 * seat, not as mutual deadlock. This works because the underlying
 * Redis lock is non-blocking ({@code SET NX}): losing a lock
 * returns immediately, we release what we held, and return 409.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private final ItineraryRepository itineraryRepository;
    private final BookingRepository bookingRepository;
    private final FlightSeatRepository flightSeatRepository;
    private final FlightRepository flightRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final WaitlistService waitlistService;
    private final SeatLockService seatLockService;
    private final FlightPricingService flightPricingService;

    @Value("${app.reservation.ttl-minutes:5}")
    private int reservationTtlMinutes;

    /** Deadlock-free lock ordering: by flightId, tiebreak by seatId. */
    private static final Comparator<LegRequest> LOCK_ORDER =
            Comparator.comparing(LegRequest::flightId).thenComparing(LegRequest::seatId);

    // ---- Reserve --------------------------------------------------------

    /**
     * Create (or return the existing) {@code RESERVED} itinerary
     * for {@code userId} across the caller-supplied legs. The
     * client supplies {@code idempotencyKey} in the
     * {@code X-Idempotency-Key} header — a duplicate call with the
     * same key returns the same itineraryId instead of
     * double-writing.
     *
     * <p>Ordering matters: locks come <em>before</em> the DB
     * inserts, and locks are taken in canonical (flightId, seatId)
     * order regardless of the order in the request body. If any
     * lock fails, previously-acquired locks are released in reverse
     * and the caller gets a single 409. If the DB insert then fails
     * for a different reason, the {@code catch} block releases every
     * lock so no seat is left held out of the pool for the whole
     * TTL.</p>
     */
    @Transactional
    public BookingItineraryDto reserve(Long userId, String idempotencyKey, ReserveRequest req) {
        // ---- 1. Idempotency short-circuit ------------------------------
        Optional<Itinerary> existing = itineraryRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Itinerary it = existing.get();
            // Same key = same session. Ownership check prevents a
            // leaked / guessed key from acting as OAuth for someone
            // else's itinerary.
            if (!Objects.equals(it.getUser().getId(), userId)) {
                throw new InvalidBookingStateException(
                        "Idempotency key belongs to a different user");
            }
            // Same key must also mean the same legs (order-sensitive
            // — leg 0 and leg 1 are meaningful positions, not a set).
            if (!sameLegs(it, req.legs())) {
                log.warn("Reserve rejected: idempotency key={} exists for different legs; caller sent {}",
                        idempotencyKey, req.legs());
                throw new InvalidBookingStateException(
                        "Idempotency key already used for a different reservation");
            }
            log.info("Reserve idempotency hit for key={} -> itineraryId={}", idempotencyKey, it.getId());
            return toDto(it, "Existing reservation returned (idempotent replay).", null);
        }

        // ---- 2. Request validation -------------------------------------
        List<LegRequest> legs = req.legs();
        assertNoDuplicateLegs(legs);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        // Load each leg's flight + verify each leg's seat exists on
        // that flight and isn't already booked, computing per-leg
        // price along the way. We collect enough intermediate state
        // to persist the leg without a second round-trip after
        // locking.
        List<LegBuild> builds = new ArrayList<>(legs.size());
        for (LegRequest leg : legs) {
            Flight flight = flightRepository.findByIdWithFlightModel(leg.flightId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Flight not found: " + leg.flightId()));

            List<SeatOccupancyRow> layout = seatRepository.findSeatOccupancy(
                    leg.flightId(), flight.getFlightModel().getId());
            SeatOccupancyRow requested = layout.stream()
                    .filter(row -> Objects.equals(row.seatId(), leg.seatId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Seat " + leg.seatId() + " does not exist on flight " + leg.flightId()));
            if (requested.isBooked()) {
                throw new SeatUnavailableException(
                        "Seat " + leg.seatId() + " on flight " + leg.flightId() + " is already booked");
            }
            long bookedCount = layout.stream().filter(SeatOccupancyRow::isBooked).count();

            Seat seatRef = seatRepository.getReferenceById(leg.seatId());
            PriceQuote quote = flightPricingService.quoteFor(flight, bookedCount);

            builds.add(new LegBuild(leg, flight, seatRef, quote));
        }

        // ---- 3. Acquire seat locks in canonical order ------------------
        Duration ttl = Duration.ofMinutes(reservationTtlMinutes);
        List<LegRequest> sorted = new ArrayList<>(legs);
        sorted.sort(LOCK_ORDER);
        List<LegRequest> acquired = new ArrayList<>(sorted.size());
        try {
            for (LegRequest leg : sorted) {
                if (!seatLockService.tryLock(leg.flightId(), leg.seatId(), idempotencyKey, ttl)) {
                    throw new SeatUnavailableException(
                            "Seat " + leg.seatId() + " on flight " + leg.flightId()
                                    + " is currently being booked by someone else");
                }
                acquired.add(leg);
            }

            // ---- 4. Persist itinerary + legs in caller order -----------
            Instant now = Instant.now();
            BigDecimal total = builds.stream()
                    .map(b -> b.quote.finalPrice())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            Itinerary itinerary = itineraryRepository.save(Itinerary.builder()
                    .user(user)
                    .status(BookingStatus.RESERVED)
                    .idempotencyKey(idempotencyKey)
                    .reservedAt(now)
                    .expiresAt(now.plus(ttl))
                    .finalPrice(total)
                    .build());

            List<Booking> persistedLegs = new ArrayList<>(builds.size());
            for (int i = 0; i < builds.size(); i++) {
                LegBuild b = builds.get(i);
                Booking booking = Booking.builder()
                        .itinerary(itinerary)
                        .legOrder(i)
                        .flight(b.flight)
                        .seat(b.seatRef)
                        .finalPrice(b.quote.finalPrice())
                        .build();
                persistedLegs.add(bookingRepository.save(booking));
            }
            itinerary.setLegs(persistedLegs);

            log.info("Reserved itineraryId={} legs={} userId={} totalPrice={} ttlMinutes={}",
                    itinerary.getId(), persistedLegs.size(), userId, total, reservationTtlMinutes);

            // Per-leg breakdowns to surface on the reserve response.
            Map<Integer, List<PriceBreakdownEntry>> breakdownByOrder = new HashMap<>();
            for (int i = 0; i < builds.size(); i++) {
                breakdownByOrder.put(i, builds.get(i).quote.breakdown());
            }
            return toDto(itinerary,
                    "Reserved. Confirm within " + reservationTtlMinutes + " minutes.",
                    breakdownByOrder);
        } catch (RuntimeException ex) {
            // Release only the locks we successfully took, in
            // reverse acquisition order. release() is compare-and-
            // delete so a concurrent caller who somehow reused the
            // slot won't be clobbered.
            for (int i = acquired.size() - 1; i >= 0; i--) {
                LegRequest leg = acquired.get(i);
                seatLockService.release(leg.flightId(), leg.seatId(), idempotencyKey);
            }
            throw ex;
        }
    }

    // ---- Confirm --------------------------------------------------------

    /**
     * Promote a {@code RESERVED} itinerary to {@code CONFIRMED}:
     * verify every leg's Redis lock is still ours, charge one
     * payment for the aggregated price, INSERT one
     * {@code flight_seats} row per leg, flip the itinerary status
     * and payment link, release every lock, run per-leg
     * {@code fully_booked} bookkeeping.
     *
     * <p>The caller must send the same {@code X-Idempotency-Key}
     * they used at reserve time. That serves as:</p>
     * <ul>
     *   <li>authorisation — a stolen itineraryId can't be confirmed
     *       without also knowing the reservation's key;</li>
     *   <li>owner-tag for every leg's Redis seat lock — if any
     *       lock has expired or been recycled, we refuse before
     *       charging;</li>
     *   <li>gateway idempotency key — forwarded to
     *       {@link PaymentService#charge} so a retried charge
     *       dedupes end-to-end.</li>
     * </ul>
     */
    @Transactional
    public BookingItineraryDto confirm(Long itineraryId, Long callerUserId,
                                       String idempotencyKey, ConfirmRequest req) {
        Itinerary itinerary = itineraryRepository.findByIdWithGraph(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found: " + itineraryId));

        if (!Objects.equals(itinerary.getUser().getId(), callerUserId)) {
            log.warn("Confirm rejected: itinerary owner={} but caller={}",
                    itinerary.getUser().getId(), callerUserId);
            throw new InvalidBookingStateException("Reservation not found for this user");
        }

        if (!Objects.equals(itinerary.getIdempotencyKey(), idempotencyKey)) {
            log.warn("Confirm rejected: itineraryId={} idempotencyKey mismatch", itineraryId);
            throw new InvalidBookingStateException("Idempotency key does not match reservation");
        }

        switch (itinerary.getStatus()) {
            case CONFIRMED -> {
                log.info("Confirm idempotency hit: itineraryId={} already CONFIRMED", itineraryId);
                return toDto(itinerary, null, null);
            }
            case CANCELLED -> throw new InvalidBookingStateException("Booking has been cancelled");
            case RESERVED -> { /* fall through */ }
        }

        // Every leg's Redis lock is the trust anchor. If any has
        // expired or been recycled, refuse BEFORE charging so the
        // "charged but no seat" race is structurally impossible.
        List<Booking> legs = itinerary.getLegs();
        for (Booking leg : legs) {
            if (!seatLockService.isHeldBy(leg.getFlight().getId(), leg.getSeat().getId(), idempotencyKey)) {
                log.warn("Confirm rejected: itineraryId={} lock lost on flightId={} seatId={}",
                        itineraryId, leg.getFlight().getId(), leg.getSeat().getId());
                throw new InvalidBookingStateException("Reservation expired; please reserve again");
            }
        }

        // One charge covers the whole itinerary. The idempotency
        // key is forwarded so PaymentService dedupes against the
        // payments.idempotency_key unique index.
        Payment payment = paymentService.charge(
                itinerary, itinerary.getFinalPrice(), req.paymentMethod(), idempotencyKey);

        // INSERT flight_seats for every leg. UNIQUE(flight_id,
        // seat_id) is the DB-side last-line defence; with the locks
        // still ours we shouldn't reach any of these lines if a
        // concurrent confirm has already inserted a row.
        Instant now = Instant.now();
        for (Booking leg : legs) {
            FlightSeat fs = FlightSeat.builder()
                    .flight(leg.getFlight())
                    .seat(leg.getSeat())
                    .bookedAt(now)
                    .build();
            flightSeatRepository.saveAndFlush(fs);
        }

        itinerary.setStatus(BookingStatus.CONFIRMED);
        itinerary.setPayment(payment);
        itinerary.setConfirmedAt(now);
        itinerary = itineraryRepository.save(itinerary);

        // Release every lock (best-effort — TTL would clear them
        // anyway). Iterate over the legs list in whatever order
        // the graph load returned; release is compare-and-delete
        // so ordering doesn't matter.
        for (Booking leg : legs) {
            seatLockService.release(leg.getFlight().getId(), leg.getSeat().getId(), idempotencyKey);
        }

        // Fully-booked flip per unique flight. Multi-leg
        // itineraries can touch different flights, so run this
        // per-flight rather than per-leg to avoid double counting.
        // can be queued
        Set<Long> touchedFlights = new HashSet<>();
        for (Booking leg : legs) {
            if (touchedFlights.add(leg.getFlight().getId())) {
                maybeFlipFullyBooked(leg.getFlight(), true);
            }
        }

        notificationService.notifyUser(itinerary.getUser(), "Booking confirmed",
                "Your itinerary " + itinerary.getId() + " is confirmed.");

        return toDto(itinerary, null, null);
    }

    // ---- Cancel --------------------------------------------------------

    /**
     * Cancel a CONFIRMED itinerary: DELETE every leg's
     * {@code flight_seats} row, refund the one payment that
     * covered the whole trip, run per-flight waitlist fan-out and
     * {@code fully_booked} bookkeeping, flip the itinerary to
     * CANCELLED.
     *
     * <p>Only CONFIRMED itineraries are cancellable. A RESERVED
     * itinerary has no payment to refund and no
     * {@code flight_seats} rows to release — its Redis locks
     * expire on their own after the TTL, so "cancel" would be a
     * no-op and is rejected explicitly instead of silently
     * succeeding.</p>
     *
     * <p>Only the itinerary's owner may cancel. A mismatched
     * {@code callerUserId} throws with an intentionally generic
     * "not found for this user" message — a stolen itineraryId
     * shouldn't leak that the id exists but belongs to someone
     * else.</p>
     *
     * <p><b>Idempotency.</b> The caller mints a fresh
     * {@code X-Idempotency-Key} for every cancel session
     * (distinct from the reserve/confirm session's key). Four
     * branches:</p>
     * <ul>
     *   <li>Status is CONFIRMED → do the cancel, stamp the key
     *       on {@code itineraries.cancellation_idempotency_key},
     *       refund with {@code "refund:" + key} forwarded to the
     *       payment gateway so its own idempotency layer dedupes
     *       too.</li>
     *   <li>Status is CANCELLED and the stored cancel key equals
     *       the incoming key → this is a retry, return the cached
     *       DTO without re-refund / re-notify / re-waitlist-
     *       promote.</li>
     *   <li>Status is CANCELLED and the stored cancel key
     *       differs → a second, independent cancel attempt on an
     *       already-cancelled itinerary. Refuse with 409.</li>
     *   <li>Status is RESERVED → refuse with 409, the caller
     *       should let the reservation expire.</li>
     * </ul>
     */
    @Transactional
    public BookingItineraryDto cancel(Long itineraryId, Long callerUserId, String idempotencyKey) {
        Itinerary itinerary = itineraryRepository.findByIdWithGraph(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found: " + itineraryId));

        if (!Objects.equals(itinerary.getUser().getId(), callerUserId)) {
            log.warn("Cancel rejected: itinerary owner={} but caller={}",
                    itinerary.getUser().getId(), callerUserId);
            throw new InvalidBookingStateException("Reservation not found for this user");
        }

        switch (itinerary.getStatus()) {
            case CANCELLED -> {
                if (Objects.equals(itinerary.getCancellationIdempotencyKey(), idempotencyKey)) {
                    log.info("Cancel idempotency hit: itineraryId={} already CANCELLED with same key",
                            itineraryId);
                    return toDto(itinerary, null, null);
                }
                throw new InvalidBookingStateException("Itinerary is already CANCELLED");
            }
            case RESERVED -> {
                log.warn("Cancel rejected: itineraryId={} is RESERVED, not CONFIRMED", itineraryId);
                throw new InvalidBookingStateException(
                        "Only confirmed itineraries can be cancelled; a reservation will expire on its own");
            }
            case CONFIRMED -> { /* fall through */ }
        }

        List<Booking> legs = itinerary.getLegs();

        // Delete every leg's flight_seats row.
        for (Booking leg : legs) {
            flightSeatRepository.deleteByFlight_IdAndSeat_Id(
                    leg.getFlight().getId(), leg.getSeat().getId());
        }

        itinerary.setStatus(BookingStatus.CANCELLED);
        itinerary.setCancelledAt(Instant.now());
        itinerary.setCancellationIdempotencyKey(idempotencyKey);
        itinerary = itineraryRepository.save(itinerary);

        // One refund covers the whole trip. Namespace the refund
        // key so a caller who (against contract) reuses the same
        // UUID across confirm and cancel doesn't collide with the
        // CHARGE row on the payments.idempotency_key unique index.
        paymentService.refund(itinerary.getPayment().getId(), "refund:" + idempotencyKey);

        // Per-unique-flight bookkeeping: fully-booked flip and
        // waitlist fan-out. Use a Set so a multi-leg itinerary
        // that (contrived, but valid) shared a flight doesn't
        // double-notify.
        Set<Long> touchedFlights = new HashSet<>();
        for (Booking leg : legs) {
            Flight f = leg.getFlight();
            if (!touchedFlights.add(f.getId())) continue;
            maybeFlipFullyBooked(f, false);
            waitlistService.notifyAllWaitersOfOpening(f);
        }

        notificationService.notifyUser(itinerary.getUser(), "Booking cancelled",
                "Your itinerary " + itineraryId + " has been cancelled.");

        return toDto(itinerary, null, null);
    }

    // ---- Read ----------------------------------------------------------

    @Transactional(readOnly = true)
    public BookingItineraryDto getItinerary(Long itineraryId) {
        Itinerary itinerary = itineraryRepository.findByIdWithGraph(itineraryId)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary not found: " + itineraryId));
        return toDto(itinerary, null, null);
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * Flip {@code flights.fully_booked} on a flight where a leg
     * of this itinerary landed.
     *
     * @param confirming  {@code true} on confirm (may need to set
     *                    to true if this was the last seat),
     *                    {@code false} on cancel (may need to
     *                    clear the flag if the flight was full).
     */
    private void maybeFlipFullyBooked(Flight flight, boolean confirming) {
        if (confirming) {
            long booked = flightSeatRepository.countByFlight_Id(flight.getId());
            int total = flight.getFlightModel().getTotalSeats();
            if (booked >= total && !flight.isFullyBooked()) {
                flight.setFullyBooked(true);
                flightRepository.save(flight);
            }
        } else {
            if (flight.isFullyBooked()) {
                flight.setFullyBooked(false);
                flightRepository.save(flight);
            }
        }
    }

    /**
     * Order-sensitive comparison: idempotency-key replay must have
     * the same legs in the same order as the original. Sets don't
     * work — leg 0 vs leg 1 is a meaningful trip semantic.
     */
    private static boolean sameLegs(Itinerary existing, List<LegRequest> incoming) {
        List<Booking> stored = existing.getLegs();
        if (stored.size() != incoming.size()) return false;
        for (int i = 0; i < stored.size(); i++) {
            Booking b = stored.get(i);
            LegRequest r = incoming.get(i);
            if (!Objects.equals(b.getFlight().getId(), r.flightId())
                    || !Objects.equals(b.getSeat().getId(), r.seatId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * A reserve request that names the same {@code (flightId, seatId)}
     * pair twice is ambiguous — you can't sit in one seat for two
     * legs of your own trip. Reject before touching the DB.
     */
    private static void assertNoDuplicateLegs(List<LegRequest> legs) {
        Set<String> seen = new HashSet<>(legs.size() * 2);
        for (LegRequest leg : legs) {
            String key = leg.flightId() + ":" + leg.seatId();
            if (!seen.add(key)) {
                throw new InvalidBookingStateException(
                        "Duplicate leg in request: flightId=" + leg.flightId()
                                + " seatId=" + leg.seatId());
            }
        }
    }

    private BookingItineraryDto toDto(Itinerary it, String message,
                                      Map<Integer, List<PriceBreakdownEntry>> breakdownByLegOrder) {
        List<BookingDto> legDtos = new ArrayList<>(it.getLegs().size());
        for (Booking b : it.getLegs()) {
            legDtos.add(BookingDto.builder()
                    .bookingId(b.getId())
                    .legOrder(b.getLegOrder())
                    .flightId(b.getFlight().getId())
                    .source(b.getFlight().getSource())
                    .destination(b.getFlight().getDestination())
                    .seatId(b.getSeat().getId())
                    .seatNumber(b.getSeat().getSeatNumber())
                    .finalPrice(b.getFinalPrice())
                    .priceBreakdown(breakdownByLegOrder == null
                            ? null : breakdownByLegOrder.get(b.getLegOrder()))
                    .build());
        }
        return BookingItineraryDto.builder()
                .itineraryId(it.getId())
                .userId(it.getUser().getId())
                .status(it.getStatus())
                .totalFinalPrice(it.getFinalPrice())
                .reservedAt(it.getReservedAt())
                .expiresAt(it.getExpiresAt())
                .confirmedAt(it.getConfirmedAt())
                .cancelledAt(it.getCancelledAt())
                .legs(legDtos)
                .message(message)
                .build();
    }

    /** Intermediate per-leg build state carried from validation to persistence. */
    private record LegBuild(LegRequest req, Flight flight, Seat seatRef, PriceQuote quote) {}
}
