package com.flightbooking.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.flightbooking.api.ItineraryController;
import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
import com.flightbooking.domain.enums.PaymentMethod;
import com.flightbooking.api.dto.LegRequest;
import com.flightbooking.api.dto.ReserveRequest;
import com.flightbooking.domain.entity.Booking;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Payment;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.enums.BookingStatus;
import com.flightbooking.domain.enums.PaymentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP contract tests for the itinerary flow. Real
 * Spring context, real transactions, real seat lock (in-memory
 * backend) — the next thing to fake is your money.
 *
 * <p>Two marquee assertions:</p>
 * <ol>
 *   <li>The <b>concurrent-race</b> test: N users hammer the same
 *       seat with simultaneous {@code POST /itinerary/reserve}
 *       calls and prove exactly one gets a 2xx. If this flakes,
 *       the seat-lock contract is broken.</li>
 *   <li>The <b>multi-leg deadlock-avoidance</b> test: two callers
 *       reserve overlapping two-leg itineraries in opposite order.
 *       Because we canonicalise lock acquisition on
 *       {@code (flightId, seatId)}, exactly one caller wins the
 *       whole itinerary — not both losing on partial-lock
 *       failure.</li>
 * </ol>
 */
class BookingIT extends AbstractIntegrationTest {

    private User alice;
    private User bob;
    private Flight leg1Flight;
    private Flight leg2Flight;
    private Seat seatA;
    private Seat seatB;
    private Seat seatC;

    @BeforeEach
    void seed() {
        alice = createUser("Alice", "a@e");
        bob = createUser("Bob", "b@e");
        FlightModel model = createModel("Boeing", 6);
        seatA = createSeat(model, "1A");
        seatB = createSeat(model, "1B");
        seatC = createSeat(model, "1C");
        Instant t0 = Instant.now().plus(Duration.ofDays(30));
        leg1Flight = createFlight(model, "BLR", "DEL",
                t0, Duration.ofHours(2), new BigDecimal("3200"));
        leg2Flight = createFlight(model, "DEL", "BOM",
                t0.plus(Duration.ofHours(4)), Duration.ofHours(2), new BigDecimal("2800"));
    }

    // ---- helpers ------------------------------------------------------

    private BookingItineraryDto reserve(Long userId, String idem, List<LegRequest> legs) throws Exception {
        MvcResult res = mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, userId)
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(legs))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(res.getResponse().getContentAsString(),
                new TypeReference<BookingItineraryDto>() {});
    }

    private BookingItineraryDto reserveSingle(Long userId, String idem, Long flightId, Long seatId)
            throws Exception {
        return reserve(userId, idem, List.of(new LegRequest(flightId, seatId)));
    }

    private BookingItineraryDto confirm(Long itineraryId, Long userId, String idem) throws Exception {
        MvcResult res = mvc.perform(post("/itinerary/" + itineraryId + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, userId)
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ConfirmRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(res.getResponse().getContentAsString(),
                new TypeReference<BookingItineraryDto>() {});
    }

    // ---- reserve -------------------------------------------------------

    @Test
    void reserve_thenConfirm_thenCancel_singleLegHappyPath() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());
        Long itineraryId = rr.itineraryId();

        assertThat(rr.status()).isEqualTo(BookingStatus.RESERVED);
        assertThat(rr.legs()).hasSize(1);
        assertThat(rr.legs().get(0).finalPrice()).isNotNull();
        assertThat(rr.legs().get(0).priceBreakdown()).isNotEmpty();
        assertThat(rr.totalFinalPrice()).isEqualTo(rr.legs().get(0).finalPrice());
        assertThat(rr.message()).contains("Reserved");

        // No flight_seats row yet; row appears only after confirm.
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg1Flight.getId(), seatA.getId()))
                .isFalse();

        confirm(itineraryId, alice.getId(), idem);

        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg1Flight.getId(), seatA.getId()))
                .isTrue();
        assertThat(paymentRepository.findAll())
                .filteredOn(p -> p.getType() == PaymentType.CHARGE).hasSize(1);

        String cancelKey = UUID.randomUUID().toString();
        mvc.perform(post("/itinerary/" + itineraryId + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, cancelKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg1Flight.getId(), seatA.getId()))
                .isFalse();
        assertThat(paymentRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("multi-leg reserve+confirm+cancel: one itinerary, N legs, one payment, N flight_seats rows")
    void reserve_thenConfirm_thenCancel_twoLegHappyPath() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserve(alice.getId(), idem, List.of(
                new LegRequest(leg1Flight.getId(), seatA.getId()),
                new LegRequest(leg2Flight.getId(), seatB.getId())));

        assertThat(rr.status()).isEqualTo(BookingStatus.RESERVED);
        assertThat(rr.legs()).hasSize(2);
        // Legs are stored in caller order (leg 0 first flown).
        assertThat(rr.legs().get(0).legOrder()).isZero();
        assertThat(rr.legs().get(1).legOrder()).isOne();
        assertThat(rr.legs().get(0).flightId()).isEqualTo(leg1Flight.getId());
        assertThat(rr.legs().get(1).flightId()).isEqualTo(leg2Flight.getId());
        assertThat(rr.totalFinalPrice())
                .isEqualByComparingTo(rr.legs().get(0).finalPrice().add(rr.legs().get(1).finalPrice()));

        confirm(rr.itineraryId(), alice.getId(), idem);

        // Both legs get their flight_seats row.
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg1Flight.getId(), seatA.getId()))
                .isTrue();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg2Flight.getId(), seatB.getId()))
                .isTrue();
        // One aggregated CHARGE, not per-leg.
        assertThat(paymentRepository.findAll())
                .filteredOn(p -> p.getType() == PaymentType.CHARGE).hasSize(1);

        // Cancel cascades: both flight_seats rows go, one REFUND row.
        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg1Flight.getId(), seatA.getId()))
                .isFalse();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg2Flight.getId(), seatB.getId()))
                .isFalse();
        assertThat(paymentRepository.findAll())
                .filteredOn(p -> p.getType() == PaymentType.REFUND).hasSize(1);
    }

    @Test
    @DisplayName("multi-leg reserve where the second leg's seat is already booked: whole reserve rolls back")
    void reserveMultiLegPartialSeatBookedRollsBack() throws Exception {
        // Pre-book seatB on leg2 so a subsequent multi-leg reserve
        // must fail on the second leg. seatA on leg1 must NOT be
        // held out of the pool after the failure.
        String bootIdem = UUID.randomUUID().toString();
        BookingItineraryDto boot = reserveSingle(bob.getId(), bootIdem, leg2Flight.getId(), seatB.getId());
        confirm(boot.itineraryId(), bob.getId(), bootIdem);

        String idem = UUID.randomUUID().toString();
        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1Flight.getId(), seatA.getId()),
                                new LegRequest(leg2Flight.getId(), seatB.getId()))))))
                .andExpect(status().isConflict());

        // No itinerary and no leg for alice.
        assertThat(itineraryRepository.findByIdempotencyKey(idem)).isEmpty();
        assertThat(bookingRepository.findByFlight_Id(leg1Flight.getId()))
                .as("leg1's seat must not be held out of the pool after a rollback")
                .isEmpty();
        // A brand new reserve for leg1 seatA must succeed — proving
        // the lock from the failed multi-leg attempt was released.
        reserveSingle(alice.getId(), UUID.randomUUID().toString(),
                leg1Flight.getId(), seatA.getId());
    }

    @Test
    @DisplayName("duplicate legs in one reserve request are rejected before any lock is taken")
    void reserveWithDuplicateLegsRejected() throws Exception {
        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1Flight.getId(), seatA.getId()),
                                new LegRequest(leg1Flight.getId(), seatA.getId()))))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("reserve replay with same idempotency key returns same itineraryId, no new row")
    void reserveIsIdempotent() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto first = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());
        BookingItineraryDto replay = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        assertThat(replay.itineraryId()).isEqualTo(first.itineraryId());
        assertThat(itineraryRepository.findAll()).hasSize(1);
        assertThat(bookingRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("reserve replay with same key but different legs is 409")
    void reserveReplayDifferentBodyRejected() throws Exception {
        String idem = UUID.randomUUID().toString();
        reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1Flight.getId(), seatB.getId()))))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("reserve replay by a different user reusing the key is 409")
    void reserveReplayDifferentUserRejected() throws Exception {
        String idem = UUID.randomUUID().toString();
        reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1Flight.getId(), seatA.getId()))))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("missing X-User-Id header is a 400, not a 500")
    void missingHeaderIs400() throws Exception {
        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1Flight.getId(), seatA.getId()))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("empty legs array → 400 via bean validation")
    void emptyLegsIs400() throws Exception {
        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legs\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("null flightId inside a leg → 400 via bean validation")
    void invalidLegBodyIs400() throws Exception {
        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legs\":[{\"seatId\": " + seatA.getId() + "}]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- confirm -------------------------------------------------------

    @Test
    @DisplayName("confirm by non-owner: 409, generic message — never a 200")
    void confirmByNonOwnerRejected() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ConfirmRequest(PaymentMethod.CARD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("not found for this user")));
    }

    @Test
    @DisplayName("confirm replay returns cached DTO without a second CHARGE row")
    void confirmIsIdempotent() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        confirm(rr.itineraryId(), alice.getId(), idem);
        confirm(rr.itineraryId(), alice.getId(), idem);

        assertThat(paymentRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("confirm with wrong idempotency key is 409")
    void confirmWrongKeyRejected() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, "different")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ConfirmRequest(PaymentMethod.CARD))))
                .andExpect(status().isConflict());
    }

    // ---- cancel -------------------------------------------------------

    @Test
    @DisplayName("cancel replay with same key returns cached DTO, no second REFUND row")
    void cancelIsIdempotent() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());
        confirm(rr.itineraryId(), alice.getId(), idem);

        String cancelKey = UUID.randomUUID().toString();
        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, cancelKey))
                .andExpect(status().isOk());
        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, cancelKey))
                .andExpect(status().isOk());

        assertThat(paymentRepository.findAll())
                .filteredOn(p -> p.getType() == PaymentType.REFUND).hasSize(1);
    }

    @Test
    @DisplayName("cancel replay with a different key on an already-CANCELLED itinerary is 409")
    void cancelDifferentKeyOnCancelledIsConflict() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());
        confirm(rr.itineraryId(), alice.getId(), idem);

        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, "k-first"))
                .andExpect(status().isOk());
        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, "k-different"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("cancel by non-owner is 409")
    void cancelByNonOwnerRejected() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());
        confirm(rr.itineraryId(), alice.getId(), idem);

        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("cancel of a RESERVED itinerary is 409 — reservations expire on their own")
    void cancelReservedItineraryIsRefused() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isConflict());

        Itinerary it = itineraryRepository.findById(rr.itineraryId()).orElseThrow();
        assertThat(it.getStatus()).isEqualTo(BookingStatus.RESERVED);
        assertThat(paymentRepository.findAll())
                .filteredOn(p -> p.getType() == PaymentType.REFUND).isEmpty();
        assertThat(flightSeatRepository.existsByFlight_IdAndSeat_Id(leg1Flight.getId(), seatA.getId()))
                .isFalse();
    }

    // ---- view ---------------------------------------------------------

    @Test
    void viewItinerary_returnsDtoForOwner() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(get("/itinerary/" + rr.itineraryId())
                        .header(ItineraryController.USER_ID_HEADER, alice.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itineraryId").value(rr.itineraryId()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.userId").value(alice.getId()))
                .andExpect(jsonPath("$.legs.length()").value(1));
    }

    @Test
    void viewItinerary_unknownIs404() throws Exception {
        mvc.perform(get("/itinerary/99999")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("view refuses non-owner with masked 409 (IDOR guard)")
    void viewItinerary_nonOwnerGets409NotAForbidden() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        // bob knows / guesses alice's itineraryId — must NOT be able
        // to read her passenger name, seat, price, etc.
        mvc.perform(get("/itinerary/" + rr.itineraryId())
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not found for this user")));
    }

    @Test
    void viewItinerary_missingUserHeaderIs400() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(get("/itinerary/" + rr.itineraryId()))
                .andExpect(status().isBadRequest());
    }

    // ---- concurrent race ---------------------------------------------

    @Test
    @DisplayName("N users race for the same seat: exactly one 2xx, the rest 409")
    void concurrentReserveExactlyOneWinner() throws Exception {
        int contenders = 16;
        List<User> racers = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            racers.add(createUser("Racer-" + i, "r" + i + "@e"));
        }

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (User racer : racers) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    int sc = mvc.perform(post("/itinerary/reserve")
                                    .header(ItineraryController.USER_ID_HEADER, racer.getId())
                                    .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                            new LegRequest(leg1Flight.getId(), seatA.getId()))))))
                            .andReturn().getResponse().getStatus();
                    if (sc == 200) successes.incrementAndGet();
                    else if (sc == 409) conflicts.incrementAndGet();
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).as("exactly one racer must reserve the seat").isEqualTo(1);
        assertThat(conflicts.get()).as("every loser must see 409, not 500")
                .isEqualTo(contenders - 1);
        assertThat(itineraryRepository.findAll()).hasSize(1);
        assertThat(bookingRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("multi-leg reserves with overlapping legs in opposite order: exactly one caller wins, no mutual deadlock")
    void concurrentMultiLegDeadlockAvoidance() throws Exception {
        // Two contending itineraries share both legs but in
        // opposite caller-order. If we locked in caller-supplied
        // order, both could grab their first lock and both fail on
        // the second — a lose-lose. The canonical (flightId,
        // seatId) sort inside the service ensures both acquire in
        // the same order, so exactly one wins the whole trip.
        int rounds = 8; // repeat to shake out flakiness
        for (int i = 0; i < rounds; i++) {
            // Fresh flight + seats per round to avoid conflating
            // with the AfterEach wipe scope.
            FlightModel model = createModel("B" + i, 6);
            Seat s1 = createSeat(model, "1A");
            Seat s2 = createSeat(model, "1B");
            Instant t = Instant.now().plus(Duration.ofDays(30 + i));
            Flight f1 = createFlight(model, "BLR", "DEL", t,
                    Duration.ofHours(2), new BigDecimal("3000"));
            Flight f2 = createFlight(model, "DEL", "BOM",
                    t.plus(Duration.ofHours(4)),
                    Duration.ofHours(2), new BigDecimal("2500"));

            User u1 = createUser("Fwd-" + i, "fwd" + i + "@e");
            User u2 = createUser("Rev-" + i, "rev" + i + "@e");

            List<LegRequest> forward = List.of(
                    new LegRequest(f1.getId(), s1.getId()),
                    new LegRequest(f2.getId(), s2.getId()));
            List<LegRequest> reverse = List.of(
                    new LegRequest(f2.getId(), s2.getId()),
                    new LegRequest(f1.getId(), s1.getId()));

            AtomicInteger wins = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch gate = new CountDownLatch(1);
                Future<?> fwd = pool.submit(() -> {
                    gate.await();
                    int sc = mvc.perform(post("/itinerary/reserve")
                                    .header(ItineraryController.USER_ID_HEADER, u1.getId())
                                    .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(new ReserveRequest(forward))))
                            .andReturn().getResponse().getStatus();
                    if (sc == 200) wins.incrementAndGet();
                    else if (sc == 409) conflicts.incrementAndGet();
                    return null;
                });
                Future<?> rev = pool.submit(() -> {
                    gate.await();
                    int sc = mvc.perform(post("/itinerary/reserve")
                                    .header(ItineraryController.USER_ID_HEADER, u2.getId())
                                    .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(new ReserveRequest(reverse))))
                            .andReturn().getResponse().getStatus();
                    if (sc == 200) wins.incrementAndGet();
                    else if (sc == 409) conflicts.incrementAndGet();
                    return null;
                });
                gate.countDown();
                fwd.get(10, TimeUnit.SECONDS);
                rev.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(wins.get())
                    .as("round %d: exactly one of the two callers must win the whole itinerary", i)
                    .isEqualTo(1);
            assertThat(conflicts.get())
                    .as("round %d: the loser must see 409, not double-loss", i)
                    .isEqualTo(1);
        }
    }

    // ---- Fix 4 / Fix 5 request-validation regressions ----------------

    @Test
    @DisplayName("reserve rejects a body with more than MAX_LEGS legs (Fix 5)")
    void reserve_rejectsBodyExceedingMaxLegs() throws Exception {
        // 9 legs — 1 above the cap. The exact leg content doesn't
        // matter; @Size fires before controller / service code.
        List<LegRequest> tooMany = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> new LegRequest(leg1Flight.getId(), seatA.getId()))
                .toList();

        mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(tooMany))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("confirm rejects an unknown paymentMethod at JSON binding time (Fix 4)")
    void confirm_rejectsUnknownPaymentMethod() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        // Hand-craft JSON with a value not in PaymentMethod.
        // Jackson must fail deserialization → 400 without ever
        // reaching PaymentService.
        String badJson = "{\"paymentMethod\":\"MONOPOLY_MONEY\"}";
        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("confirm rejects a null paymentMethod as 400 (Fix 4)")
    void confirm_rejectsNullPaymentMethod() throws Exception {
        String idem = UUID.randomUUID().toString();
        BookingItineraryDto rr = reserveSingle(alice.getId(), idem, leg1Flight.getId(), seatA.getId());

        mvc.perform(post("/itinerary/" + rr.itineraryId() + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":null}"))
                .andExpect(status().isBadRequest());
    }
}
