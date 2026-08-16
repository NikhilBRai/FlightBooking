package com.flightbooking.service;

import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
import com.flightbooking.domain.enums.PaymentMethod;
import com.flightbooking.api.dto.LegRequest;
import com.flightbooking.api.dto.PriceBreakdownEntry;
import com.flightbooking.api.dto.ReserveRequest;
import com.flightbooking.domain.entity.Booking;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Itinerary;
import com.flightbooking.domain.entity.Payment;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.enums.BookingStatus;
import com.flightbooking.domain.enums.PaymentStatus;
import com.flightbooking.domain.enums.PaymentType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exhaustive unit coverage of the itinerary lifecycle service.
 * Structured by phase — reserve, confirm, cancel, get — so a
 * regression in one path is easy to trace.
 *
 * <p>The multi-leg-specific properties (canonical lock ordering,
 * all-or-nothing lock acquisition, all-or-nothing DB write) get
 * their own dedicated cases in the {@link Reserve} nest — those are
 * what makes the atomicity contract non-trivial and where a
 * refactor is most likely to silently regress.</p>
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock ItineraryRepository itineraryRepository;
    @Mock BookingRepository bookingRepository;
    @Mock FlightSeatRepository flightSeatRepository;
    @Mock FlightRepository flightRepository;
    @Mock SeatRepository seatRepository;
    @Mock UserRepository userRepository;
    @Mock PaymentService paymentService;
    @Mock NotificationService notificationService;
    @Mock WaitlistService waitlistService;
    @Mock SeatLockService seatLockService;
    @Mock FlightPricingService flightPricingService;

    @InjectMocks BookingService svc;

    private User alice;
    private FlightModel model;
    private Flight flightA;
    private Flight flightB;
    private Seat seatA;
    private Seat seatB;

    /**
     * Backing store for the bulk-flight-fetch stub. Populated by
     * {@link #arrangeFlight} and consumed by the lenient
     * {@code findAllByIdInWithFlightModel} stub installed in
     * {@link #setUp}. Reset per-test so cross-test leakage is
     * impossible.
     */
    private final Map<Long, Flight> stubbedFlightsById = new HashMap<>();

    /**
     * Backing store for the bulk-layout stub. Same shape as
     * {@link #stubbedFlightsById}: {@link #arrangeSeatLayout}
     * accumulates rows keyed by flightId, and the lenient
     * {@code findSeatOccupancyForFlights} stub in {@link #setUp}
     * flattens the requested ids' entries. BookingService.reserve
     * now issues one bulk layout query per invocation instead of
     * N per-leg lookups, so this shape matches the API.
     */
    private final Map<Long, List<SeatOccupancyRow>> stubbedLayoutByFlightId = new HashMap<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(svc, "reservationTtlMinutes", 5);
        // Match application.yml defaults. Without these, the
        // @Value-injected primitives stay at Java's default (0)
        // — minLayoverMinutes=0 makes the layover-min check a no-op,
        // and maxLayoverHours=0 makes EVERY layover exceed max.
        ReflectionTestUtils.setField(svc, "minLayoverMinutes", 60L);
        ReflectionTestUtils.setField(svc, "maxLayoverHours", 12L);
        stubbedFlightsById.clear();
        stubbedLayoutByFlightId.clear();
        // Lenient because tests that never reach BookingService.reserve
        // (e.g. Confirm, Cancel, GetItinerary nested classes) won't
        // hit this stub, and strict Mockito would flag it as unused.
        lenient().when(flightRepository.findAllByIdInWithFlightModel(any()))
                .thenAnswer(inv -> {
                    Collection<Long> ids = inv.getArgument(0);
                    return ids.stream()
                            .map(stubbedFlightsById::get)
                            .filter(Objects::nonNull)
                            .toList();
                });
        // Bulk layout stub — flattens rows for the requested flight
        // ids so a caller-driven set order is preserved. Lenient for
        // the same reason as the flight stub above.
        lenient().when(seatRepository.findSeatOccupancyForFlights(any()))
                .thenAnswer(inv -> {
                    Collection<Long> ids = inv.getArgument(0);
                    return ids.stream()
                            .flatMap(id -> stubbedLayoutByFlightId
                                    .getOrDefault(id, List.of()).stream())
                            .toList();
                });
        alice = User.builder().id(1L).name("Alice").email("a@e").build();
        model = FlightModel.builder().id(1L).make("Boeing").totalSeats(6).build();
        // Fixture design note: flightA (id=10) is the LATER-flown leg
        // (DEL->BOM at 14:00) and flightB (id=11) is the EARLIER-flown
        // leg (BLR->DEL at 08:00). A valid connecting itinerary in
        // caller-chronological order is therefore [flightB, flightA]
        // (BLR->DEL->BOM with a 4h layover in DEL). This layout lets
        // us exercise the "caller order != canonical (flightId asc)
        // lock order" case without violating the multi-leg
        // connectivity check enforced by BookingService.reserve.
        flightA = Flight.builder().id(10L).flightModel(model)
                .source("DEL").destination("BOM").cost(new BigDecimal("2500"))
                .startTime(Instant.parse("2030-01-01T14:00:00Z"))
                .endTime(Instant.parse("2030-01-01T16:00:00Z"))
                .fullyBooked(false).build();
        flightB = Flight.builder().id(11L).flightModel(model)
                .source("BLR").destination("DEL").cost(new BigDecimal("3000"))
                .startTime(Instant.parse("2030-01-01T08:00:00Z"))
                .endTime(Instant.parse("2030-01-01T10:00:00Z"))
                .fullyBooked(false).build();
        seatA = Seat.builder().id(100L).seatNumber("1A").flightModel(model).build();
        seatB = Seat.builder().id(101L).seatNumber("1B").flightModel(model).build();
    }

    // ================================================================
    // Reserve
    // ================================================================

    @Nested
    class Reserve {

        @Test
        void singleLegHappyPath_persistsItineraryPlusOneLeg() {
            arrangeMissedIdempotency("k-new");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeSeatLayout(flightA.getId(), model.getId(),
                    seatRow(seatA, false), seatRow(seatB, false));
            arrangeSeatRef(seatA);
            arrangeQuote(flightA, 0, new BigDecimal("3200"));
            arrangeLockSucceeds();
            arrangeItinerarySave();
            arrangeBookingSave();

            BookingItineraryDto out = svc.reserve(alice.getId(), "k-new",
                    request(new LegRequest(flightA.getId(), seatA.getId())));

            assertThat(out.status()).isEqualTo(BookingStatus.RESERVED);
            assertThat(out.legs()).hasSize(1);
            assertThat(out.legs().get(0).legOrder()).isZero();
            assertThat(out.legs().get(0).finalPrice()).isEqualByComparingTo("3200");
            assertThat(out.totalFinalPrice()).isEqualByComparingTo("3200");
            assertThat(out.message()).contains("Reserved");

            // One lock, one itinerary save, one booking save.
            verify(seatLockService).tryLock(flightA.getId(), seatA.getId(), "k-new",
                    Duration.ofMinutes(5));
            verify(itineraryRepository).save(any(Itinerary.class));
            // saveAll — single bulk-persist replaces per-leg save loop.
            verify(bookingRepository).saveAll(argThat(
                    (Iterable<Booking> it) -> countLegs(it) == 1));
            verify(seatLockService, never()).release(anyLong(), anyLong(), any());
        }

        @Test
        void twoLegHappyPath_persistsInCallerOrderWithBothLocksAcquired() {
            arrangeMissedIdempotency("k-multi");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeFlight(flightB);
            arrangeSeatLayout(flightA.getId(), model.getId(), seatRow(seatA, false));
            arrangeSeatLayout(flightB.getId(), model.getId(), seatRow(seatB, false));
            arrangeSeatRef(seatA);
            arrangeSeatRef(seatB);
            arrangeQuote(flightA, 0, new BigDecimal("3200"));
            arrangeQuote(flightB, 0, new BigDecimal("2800"));
            arrangeLockSucceeds();
            arrangeItinerarySave();
            arrangeBookingSave();

            // Caller-chronological order for the fixture is
            // [flightB (BLR->DEL earlier), flightA (DEL->BOM later)].
            // See the fixture Javadoc for why flightB is the earlier
            // leg despite the alphabetical order of the names.
            BookingItineraryDto out = svc.reserve(alice.getId(), "k-multi", request(
                    new LegRequest(flightB.getId(), seatB.getId()),
                    new LegRequest(flightA.getId(), seatA.getId())));

            assertThat(out.legs()).hasSize(2);
            assertThat(out.legs().get(0).legOrder()).isZero();
            assertThat(out.legs().get(0).flightId()).isEqualTo(flightB.getId());
            assertThat(out.legs().get(1).legOrder()).isOne();
            assertThat(out.legs().get(1).flightId()).isEqualTo(flightA.getId());
            assertThat(out.totalFinalPrice()).isEqualByComparingTo("6000");

            verify(seatLockService).tryLock(flightA.getId(), seatA.getId(), "k-multi", Duration.ofMinutes(5));
            verify(seatLockService).tryLock(flightB.getId(), seatB.getId(), "k-multi", Duration.ofMinutes(5));
            verify(itineraryRepository).save(any(Itinerary.class));
            // saveAll receives both legs in one bulk call.
            verify(bookingRepository).saveAll(argThat(
                    (Iterable<Booking> it) -> countLegs(it) == 2));
        }

        @Test
        @DisplayName("caller order reversed vs canonical: legs persist in caller order, locks in canonical order")
        void reversedCallerOrder_locksCanonically_persistsInCallerOrder() {
            arrangeMissedIdempotency("k-rev");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeFlight(flightB);
            arrangeSeatLayout(flightA.getId(), model.getId(), seatRow(seatA, false));
            arrangeSeatLayout(flightB.getId(), model.getId(), seatRow(seatB, false));
            arrangeSeatRef(seatA);
            arrangeSeatRef(seatB);
            arrangeQuote(flightA, 0, new BigDecimal("3200"));
            arrangeQuote(flightB, 0, new BigDecimal("2800"));
            arrangeLockSucceeds();
            arrangeItinerarySave();
            arrangeBookingSave();

            // Caller order = [flightB (id=11), flightA (id=10)].
            // Canonical (flightId asc) = [flightA (id=10), flightB (id=11)].
            // These differ → the test verifies the reordering.
            BookingItineraryDto out = svc.reserve(alice.getId(), "k-rev", request(
                    new LegRequest(flightB.getId(), seatB.getId()),
                    new LegRequest(flightA.getId(), seatA.getId())));

            // (a) Legs must persist in caller order — leg 0 is what
            //     the client sent first, regardless of internal
            //     canonical ordering.
            assertThat(out.legs()).extracting(l -> l.flightId())
                    .containsExactly(flightB.getId(), flightA.getId());
            assertThat(out.legs()).extracting(l -> l.legOrder())
                    .containsExactly(0, 1);

            // (b) Locks must fire in CANONICAL (flightId asc) order,
            //     not caller order. This is the deadlock-avoidance
            //     invariant: two concurrent reserves for the same
            //     seats resolve to first-writer-wins on the smallest
            //     seat, never mutual deadlock. InOrder makes the
            //     ordering explicit — a regression that flipped it
            //     back to caller order would silently pass a bare
            //     verify().
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(seatLockService);
            inOrder.verify(seatLockService).tryLock(
                    flightA.getId(), seatA.getId(), "k-rev", Duration.ofMinutes(5));
            inOrder.verify(seatLockService).tryLock(
                    flightB.getId(), seatB.getId(), "k-rev", Duration.ofMinutes(5));
        }

        @Test
        void duplicateLegsInRequestAreRejectedBeforeAnyLockOrDb() {
            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-dup", request(
                            new LegRequest(flightA.getId(), seatA.getId()),
                            new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("Duplicate leg");

            verify(seatLockService, never()).tryLock(anyLong(), anyLong(), anyString(), any());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        void seatAlreadyBookedInLayoutYields409() {
            // Ordering note: the layout query runs INSIDE the seat
            // lock (see BookingService.reserve phase 3 Javadoc), so
            // we DO acquire the lock briefly here and then release
            // it once the layout tells us the seat is already
            // booked. That tiny lock-and-release is the price of
            // making the layout answer authoritative — the trade
            // for eliminating the pre-lock read-then-lock race.
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeSeatLayout(flightA.getId(), model.getId(),
                    seatRow(seatA, true), seatRow(seatB, false));
            arrangeLockSucceeds();

            assertThatExceptionOfType(SeatUnavailableException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("is already booked");
            verify(seatLockService).tryLock(eq(flightA.getId()), eq(seatA.getId()), eq("k"), any());
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k");
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        void seatNotInLayoutYields404() {
            // Same lock-and-release trade-off as
            // seatAlreadyBookedInLayoutYields409: we discover the
            // seat doesn't exist on the model AFTER acquiring the
            // Redis lock, then release it via the reserve catch
            // block. Safe (Redis lock on a nonexistent seat is
            // harmless) and worth the price for the hot-path win.
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeSeatLayout(flightA.getId(), model.getId(), seatRow(seatB, false));
            arrangeLockSucceeds();

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("Seat " + seatA.getId());
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k");
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        void flightNotFoundYields404() {
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            // No arrangeFlight() — the lenient bulk stub in setUp
            // returns an empty list for the requested id, and
            // reserve's per-leg cross-check surfaces the specific
            // missing id as a 404.
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("Flight not found: " + flightA.getId());
        }

        @Test
        void unknownUserYields404() {
            arrangeMissedIdempotency("k");
            when(userRepository.findById(alice.getId())).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))));
        }

        @Test
        @DisplayName("second lock (canonical order) fails: first successfully-taken lock is released, no itinerary persisted")
        void partialLockFailure_releasesAcquiredLocksAndRollsBack() {
            arrangeMissedIdempotency("k-part");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeFlight(flightB);
            // No layout / seatRef / quote stubs are needed. Phase 3a
            // (acquire every lock in canonical order) fails on
            // flightB before phase 3b (bulk layout query) or phase 3c
            // (per-leg validation) run — those side-effects are
            // never touched, so stubbing them here would be
            // UnnecessaryStubbing under Mockito strict.

            // Locks are acquired in canonical order [flightA (id=10),
            // flightB (id=11)]. flightA succeeds; flightB fails.
            when(seatLockService.tryLock(eq(flightA.getId()), eq(seatA.getId()), eq("k-part"), any()))
                    .thenReturn(true);
            when(seatLockService.tryLock(eq(flightB.getId()), eq(seatB.getId()), eq("k-part"), any()))
                    .thenReturn(false);

            // Caller order is [flightB, flightA] — valid connecting
            // itinerary under the fixture. Reserve internally sorts
            // by canonical (flightId asc) before locking, so flightA
            // is tried first.
            assertThatExceptionOfType(SeatUnavailableException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-part", request(
                            new LegRequest(flightB.getId(), seatB.getId()),
                            new LegRequest(flightA.getId(), seatA.getId()))));

            // flightA's lock (successfully taken) must be released;
            // flightB's lock (never taken) must NOT be released
            // (we don't own it, and doing so could clobber whoever
            // does). No itinerary should have been persisted.
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k-part");
            verify(seatLockService, never()).release(eq(flightB.getId()), eq(seatB.getId()), anyString());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
            // Neither the per-leg save (removed) nor the bulk saveAll
            // should fire when the reservation is rejected before persist.
            verify(bookingRepository, never()).save(any(Booking.class));
            verify(bookingRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("first (and only) lock fails: nothing to release, no itinerary persisted")
        void firstLockFailureShortCircuits_noReleaseNoPersist() {
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            // Layout / seatRef / quote are NOT stubbed — the
            // tryLock failure exits phase 3 before any of those
            // are reached, and Mockito strict would flag them.
            when(seatLockService.tryLock(eq(flightA.getId()), eq(seatA.getId()), anyString(), any()))
                    .thenReturn(false);

            assertThatExceptionOfType(SeatUnavailableException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))));
            verify(seatLockService, never()).release(anyLong(), anyLong(), any());
        }

        // ---- Idempotency short-circuit paths -----------------------

        @Test
        void idempotencyReplaySameLegsReturnsCachedDto() {
            Itinerary existing = itinerary("k1", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdempotencyKey("k1"))
                    .thenReturn(Optional.of(existing));

            BookingItineraryDto out = svc.reserve(alice.getId(), "k1",
                    request(new LegRequest(flightA.getId(), seatA.getId())));

            assertThat(out.itineraryId()).isEqualTo(existing.getId());
            assertThat(out.message()).contains("idempotent replay");
            verify(seatLockService, never()).tryLock(anyLong(), anyLong(), any(), any());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        void idempotencyReplayDifferentUserRejected() {
            Itinerary existing = itinerary("k1", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdempotencyKey("k1"))
                    .thenReturn(Optional.of(existing));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(999L, "k1",
                            request(new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("different user");
        }

        @Test
        void idempotencyReplayDifferentLegsRejected() {
            Itinerary existing = itinerary("k1", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdempotencyKey("k1"))
                    .thenReturn(Optional.of(existing));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k1",
                            request(new LegRequest(flightA.getId(), seatB.getId()))))
                    .withMessageContaining("different reservation");
        }

        @Test
        void idempotencyReplayMultiLegDifferentOrderRejected() {
            Itinerary existing = itinerary("k1", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0), leg(flightB, seatB, 1)));
            when(itineraryRepository.findByIdempotencyKey("k1"))
                    .thenReturn(Optional.of(existing));

            // Same legs, opposite order → different itinerary intent.
            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k1", request(
                            new LegRequest(flightB.getId(), seatB.getId()),
                            new LegRequest(flightA.getId(), seatA.getId()))));
        }

        // ---- Fix 2 / Fix 3 / Fix 7 regression tests ------------------

        @Test
        @DisplayName("reserve refuses a flight whose startTime is in the past (Fix 2)")
        void reserveOnDepartedFlightIsRejected() {
            arrangeMissedIdempotency("k-past");
            arrangeUserLoad(alice);
            Flight past = Flight.builder().id(30L).flightModel(model)
                    .source("BLR").destination("BOM").cost(new BigDecimal("1000"))
                    .startTime(Instant.now().minusSeconds(60))
                    .endTime(Instant.now().plusSeconds(60))
                    .fullyBooked(false).build();
            arrangeFlight(past);

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-past",
                            request(new LegRequest(past.getId(), seatA.getId()))))
                    .withMessageContaining("departed");

            verify(seatLockService, never()).tryLock(anyLong(), anyLong(), anyString(), any());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        @DisplayName("reserve rejects multi-leg with mismatched connection (Fix 3)")
        void reserveMultiLegDisconnectedAirportsRejected() {
            arrangeMissedIdempotency("k-disc");
            arrangeUserLoad(alice);
            // Two flights that DO NOT connect: legX BLR->DEL, legY
            // MUMBAI->HYD (destination of first != source of second).
            Flight legX = Flight.builder().id(40L).flightModel(model)
                    .source("BLR").destination("DEL").cost(new BigDecimal("3000"))
                    .startTime(Instant.parse("2030-01-01T08:00:00Z"))
                    .endTime(Instant.parse("2030-01-01T10:00:00Z"))
                    .fullyBooked(false).build();
            Flight legY = Flight.builder().id(41L).flightModel(model)
                    .source("BOM").destination("HYD").cost(new BigDecimal("2500"))
                    .startTime(Instant.parse("2030-01-01T14:00:00Z"))
                    .endTime(Instant.parse("2030-01-01T16:00:00Z"))
                    .fullyBooked(false).build();
            arrangeFlight(legX);
            arrangeFlight(legY);
            // Connectivity check fires in phase 2 (before any lock),
            // so layout / seatRef / quote are never touched — stubs
            // for them would be UnnecessaryStubbing under Mockito strict.

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-disc", request(
                            new LegRequest(legX.getId(), seatA.getId()),
                            new LegRequest(legY.getId(), seatB.getId()))))
                    .withMessageContaining("does not connect");

            verify(seatLockService, never()).tryLock(anyLong(), anyLong(), anyString(), any());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        @DisplayName("reserve rejects multi-leg where layover is below min (Fix 3)")
        void reserveMultiLegInsufficientLayoverRejected() {
            arrangeMissedIdempotency("k-tight");
            arrangeUserLoad(alice);
            // 30-min gap in DEL — below default 60-min minLayover.
            Flight leg1 = Flight.builder().id(50L).flightModel(model)
                    .source("BLR").destination("DEL").cost(new BigDecimal("3000"))
                    .startTime(Instant.parse("2030-01-01T08:00:00Z"))
                    .endTime(Instant.parse("2030-01-01T10:00:00Z"))
                    .fullyBooked(false).build();
            Flight leg2 = Flight.builder().id(51L).flightModel(model)
                    .source("DEL").destination("BOM").cost(new BigDecimal("2500"))
                    .startTime(Instant.parse("2030-01-01T10:30:00Z"))
                    .endTime(Instant.parse("2030-01-01T12:30:00Z"))
                    .fullyBooked(false).build();
            arrangeFlight(leg1);
            arrangeFlight(leg2);
            // Layover check fires in phase 2 (before any lock), so
            // layout / seatRef / quote are never touched.

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-tight", request(
                            new LegRequest(leg1.getId(), seatA.getId()),
                            new LegRequest(leg2.getId(), seatB.getId()))))
                    .withMessageContaining("Layover");
        }

        @Test
        @DisplayName("reserve rejects multi-leg where layover exceeds max (Fix 3)")
        void reserveMultiLegExcessiveLayoverRejected() {
            arrangeMissedIdempotency("k-huge");
            arrangeUserLoad(alice);
            // 20-hour gap in DEL — above default 12h maxLayover.
            // Keeps reserve's accepted shape equal to what search
            // would ever return; a caller who genuinely wants a
            // 20h stopover can book as two separate itineraries.
            Flight leg1 = Flight.builder().id(70L).flightModel(model)
                    .source("BLR").destination("DEL").cost(new BigDecimal("3000"))
                    .startTime(Instant.parse("2030-01-01T08:00:00Z"))
                    .endTime(Instant.parse("2030-01-01T10:00:00Z"))
                    .fullyBooked(false).build();
            Flight leg2 = Flight.builder().id(71L).flightModel(model)
                    .source("DEL").destination("BOM").cost(new BigDecimal("2500"))
                    .startTime(Instant.parse("2030-01-02T06:00:00Z"))
                    .endTime(Instant.parse("2030-01-02T08:00:00Z"))
                    .fullyBooked(false).build();
            arrangeFlight(leg1);
            arrangeFlight(leg2);
            // Max-layover check fires in phase 2 (before any lock),
            // so layout / seatRef / quote are never touched.

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-huge", request(
                            new LegRequest(leg1.getId(), seatA.getId()),
                            new LegRequest(leg2.getId(), seatB.getId()))))
                    .withMessageContaining("maximum")
                    .withMessageContaining("separate itineraries");

            verify(seatLockService, never()).tryLock(anyLong(), anyLong(), anyString(), any());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
        }

        @Test
        @DisplayName("reserve rejects multi-leg where legs are time-reversed (Fix 3)")
        void reserveMultiLegChronologyReversedRejected() {
            arrangeMissedIdempotency("k-rev-time");
            arrangeUserLoad(alice);
            // leg1 lands at DEL at 10:00, leg2 departs from DEL at
            // 07:00 — before leg1 even lands.
            Flight leg1 = Flight.builder().id(60L).flightModel(model)
                    .source("BLR").destination("DEL").cost(new BigDecimal("3000"))
                    .startTime(Instant.parse("2030-01-01T08:00:00Z"))
                    .endTime(Instant.parse("2030-01-01T10:00:00Z"))
                    .fullyBooked(false).build();
            Flight leg2 = Flight.builder().id(61L).flightModel(model)
                    .source("DEL").destination("BOM").cost(new BigDecimal("2500"))
                    .startTime(Instant.parse("2030-01-01T07:00:00Z"))
                    .endTime(Instant.parse("2030-01-01T09:00:00Z"))
                    .fullyBooked(false).build();
            arrangeFlight(leg1);
            arrangeFlight(leg2);
            // Chronology check fires in phase 2 (before any lock),
            // so layout / seatRef / quote are never touched.

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-rev-time", request(
                            new LegRequest(leg1.getId(), seatA.getId()),
                            new LegRequest(leg2.getId(), seatB.getId()))))
                    .withMessageContaining("departs");
        }

    }

    // ================================================================
    // Confirm
    // ================================================================

    @Nested
    class Confirm {

        @Test
        void happyPath_chargesOnceInsertsFlightSeatsFlipsStatus() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(seatLockService.isHeldBy(flightA.getId(), seatA.getId(), "k")).thenReturn(true);
            Payment p = Payment.builder().id(77L).type(PaymentType.CHARGE)
                    .status(PaymentStatus.SUCCESS).amount(new BigDecimal("3200"))
                    .paymentMethod(PaymentMethod.CARD).idempotencyKey("k")
                    .transactionId("txn").itinerary(it).build();
            when(paymentService.charge(eq(it), any(), eq(PaymentMethod.CARD), eq("k"))).thenReturn(p);
            when(itineraryRepository.save(it)).thenReturn(it);
            when(flightSeatRepository.countByFlight_Id(flightA.getId())).thenReturn(1L);

            BookingItineraryDto out = svc.confirm(it.getId(), alice.getId(), "k",
                    new ConfirmRequest(PaymentMethod.CARD));

            assertThat(out.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(it.getPayment()).isSameAs(p);
            // saveAllAndFlush — single bulk-insert replaces the per-leg
            // saveAndFlush loop. The flush still happens (so a UNIQUE
            // constraint violation surfaces here as 409, not at commit).
            verify(flightSeatRepository).saveAllAndFlush(any());
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k");
            verify(notificationService).notifyUser(eq(alice), any(), any());
        }

        @Test
        void multiLegHappyPath_chargesOnceInsertsAllFlightSeatsReleasesAllLocks() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0), leg(flightB, seatB, 1)));
            it.setFinalPrice(new BigDecimal("6000"));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(seatLockService.isHeldBy(anyLong(), anyLong(), eq("k"))).thenReturn(true);
            Payment p = Payment.builder().id(77L).type(PaymentType.CHARGE)
                    .status(PaymentStatus.SUCCESS).amount(new BigDecimal("6000"))
                    .paymentMethod(PaymentMethod.CARD).idempotencyKey("k")
                    .transactionId("txn").itinerary(it).build();
            when(paymentService.charge(eq(it), any(), eq(PaymentMethod.CARD), eq("k"))).thenReturn(p);
            when(itineraryRepository.save(it)).thenReturn(it);
            when(flightSeatRepository.countByFlight_Id(anyLong())).thenReturn(1L);

            svc.confirm(it.getId(), alice.getId(), "k", new ConfirmRequest(PaymentMethod.CARD));

            // One aggregated charge, one bulk saveAllAndFlush carrying
            // both flight_seats rows, two lock releases.
            verify(paymentService, times(1)).charge(eq(it), any(), eq(PaymentMethod.CARD), eq("k"));
            verify(flightSeatRepository).saveAllAndFlush(argThat(
                    (Iterable<com.flightbooking.domain.entity.FlightSeat> in) -> countLegs(in) == 2));
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k");
            verify(seatLockService).release(flightB.getId(), seatB.getId(), "k");
        }

        @Test
        void alreadyConfirmed_returnsCachedDtoWithoutRechargeOrInsert() {
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            svc.confirm(it.getId(), alice.getId(), "k", new ConfirmRequest(PaymentMethod.CARD));

            verify(paymentService, never()).charge(any(), any(), any(), any());
            // Neither the legacy per-leg saveAndFlush nor the bulk
            // saveAllAndFlush should fire on the idempotent replay path.
            verify(flightSeatRepository, never()).saveAndFlush(any());
            verify(flightSeatRepository, never()).saveAllAndFlush(any());
        }

        @Test
        void cancelledItineraryCantBeConfirmed() {
            Itinerary it = itinerary("k", BookingStatus.CANCELLED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), alice.getId(), "k",
                            new ConfirmRequest(PaymentMethod.CARD)))
                    .withMessageContaining("cancelled");
        }

        @Test
        void nonOwnerCantConfirm() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), 999L, "k",
                            new ConfirmRequest(PaymentMethod.CARD)))
                    .withMessageContaining("not found for this user");
        }

        @Test
        void wrongIdempotencyKeyIsRefused() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), alice.getId(), "different",
                            new ConfirmRequest(PaymentMethod.CARD)))
                    .withMessageContaining("Idempotency key");
        }

        @Test
        @DisplayName("any leg's lock lost: refuse BEFORE charging, no partial persistence")
        void lockLostOnAnyLegRefusesBeforeCharging() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0), leg(flightB, seatB, 1)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(seatLockService.isHeldBy(flightA.getId(), seatA.getId(), "k")).thenReturn(true);
            when(seatLockService.isHeldBy(flightB.getId(), seatB.getId(), "k")).thenReturn(false);

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), alice.getId(), "k",
                            new ConfirmRequest(PaymentMethod.CARD)))
                    .withMessageContaining("expired");

            verify(paymentService, never()).charge(any(), any(), any(), any());
            verify(flightSeatRepository, never()).saveAndFlush(any());
            verify(flightSeatRepository, never()).saveAllAndFlush(any());
        }

        @Test
        void unknownItineraryIdYields404() {
            when(itineraryRepository.findByIdWithGraph(1L)).thenReturn(Optional.empty());
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.confirm(1L, alice.getId(), "k",
                            new ConfirmRequest(PaymentMethod.CARD)));
        }

        @Test
        void singleLegFullyBookedFlipsWhenLastSeatConfirmed() {
            // Total seats 2, count returns 2 → flip to fullyBooked.
            FlightModel small = FlightModel.builder().id(2L).make("Small").totalSeats(2).build();
            Flight solo = Flight.builder().id(20L).flightModel(small)
                    .source("BLR").destination("BOM").fullyBooked(false)
                    .cost(new BigDecimal("1000"))
                    // Strictly in the future: confirm's not-departed guard requires
                    // startTime > now, so a same-instant value would flake.
                    .startTime(Instant.now().plusSeconds(3600))
                    .endTime(Instant.now().plusSeconds(3600 + 60)).build();
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(solo, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(seatLockService.isHeldBy(anyLong(), anyLong(), any())).thenReturn(true);
            Payment p = Payment.builder().id(1L).type(PaymentType.CHARGE)
                    .status(PaymentStatus.SUCCESS).amount(new BigDecimal("3200"))
                    .paymentMethod(PaymentMethod.CARD).transactionId("t").idempotencyKey("k").itinerary(it).build();
            when(paymentService.charge(any(), any(), any(), any())).thenReturn(p);
            when(itineraryRepository.save(it)).thenReturn(it);
            when(flightSeatRepository.countByFlight_Id(solo.getId())).thenReturn(2L);

            svc.confirm(it.getId(), alice.getId(), "k", new ConfirmRequest(PaymentMethod.CARD));

            ArgumentCaptor<Flight> flightSave = ArgumentCaptor.forClass(Flight.class);
            verify(flightRepository).save(flightSave.capture());
            assertThat(flightSave.getValue().isFullyBooked()).isTrue();
        }

        @Test
        @DisplayName("confirm refuses once the flight has departed (Fix 2)")
        void confirmAfterFlightDepartedIsRejected() {
            Flight departed = Flight.builder().id(70L).flightModel(model)
                    .source("BLR").destination("BOM").fullyBooked(false)
                    .cost(new BigDecimal("1000"))
                    // Departed 1 minute ago — reservation was made
                    // before departure, but the user is trying to
                    // confirm too late.
                    .startTime(Instant.now().minusSeconds(60))
                    .endTime(Instant.now().plusSeconds(60)).build();
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(departed, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), alice.getId(), "k",
                            new ConfirmRequest(PaymentMethod.CARD)))
                    .withMessageContaining("departed");

            // No charge, no seat insert, no lock release — the check
            // fires before any side-effect.
            verify(paymentService, never()).charge(any(), any(), any(), any());
            verify(flightSeatRepository, never()).saveAndFlush(any());
            verify(flightSeatRepository, never()).saveAllAndFlush(any());
        }
    }

    // ================================================================
    // Cancel
    // ================================================================

    @Nested
    class Cancel {

        @Test
        void confirmedItineraryCancels_deletesFlightSeatsRefundsPaymentPromotesWaitlist() {
            Payment charge = Payment.builder().id(50L).type(PaymentType.CHARGE)
                    .amount(new BigDecimal("3200")).paymentMethod(PaymentMethod.CARD)
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0)));
            it.setPayment(charge);
            charge.setItinerary(it);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(itineraryRepository.save(it)).thenReturn(it);

            svc.cancel(it.getId(), alice.getId(), "cancel-1");

            // Bulk delete: one query for the whole itinerary via
            // correlated EXISTS against Booking, no per-leg loop.
            verify(flightSeatRepository).deleteAllByItinerary_Id(it.getId());
            verify(paymentService).refund(50L, "refund:cancel-1");
            verify(waitlistService).notifyAllWaitersOfOpening(flightA);
            verify(notificationService).notifyUser(eq(alice), any(), any());
            assertThat(it.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(it.getCancellationIdempotencyKey()).isEqualTo("cancel-1");
        }

        @Test
        void multiLegCancel_deletesEveryFlightSeatRefundsOnceFansOutPerFlight() {
            Payment charge = Payment.builder().id(50L).type(PaymentType.CHARGE)
                    .amount(new BigDecimal("6000")).paymentMethod(PaymentMethod.CARD)
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0), leg(flightB, seatB, 1)));
            it.setPayment(charge);
            charge.setItinerary(it);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(itineraryRepository.save(it)).thenReturn(it);

            svc.cancel(it.getId(), alice.getId(), "cancel-multi");

            // Single bulk delete covers every leg — the EXISTS subquery
            // resolves both (flight, seat) pairs in one round-trip.
            verify(flightSeatRepository).deleteAllByItinerary_Id(it.getId());
            verify(flightSeatRepository, never()).deleteByFlight_IdAndSeat_Id(any(), any());
            verify(paymentService, times(1)).refund(50L, "refund:cancel-multi");
            verify(waitlistService).notifyAllWaitersOfOpening(flightA);
            verify(waitlistService).notifyAllWaitersOfOpening(flightB);
        }

        @Test
        void reservedItineraryCannotBeCancelled() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.cancel(it.getId(), alice.getId(), "cancel-1"))
                    .withMessageContaining("Only confirmed");

            verify(flightSeatRepository, never()).deleteByFlight_IdAndSeat_Id(any(), any());
            verify(flightSeatRepository, never()).deleteAllByItinerary_Id(any());
            verify(paymentService, never()).refund(any(), any());
            verify(waitlistService, never()).notifyAllWaitersOfOpening(any());
        }

        @Test
        void cancelledSameKey_isIdempotentReturnsCachedDto() {
            Payment charge = Payment.builder().id(1L).type(PaymentType.CHARGE)
                    .amount(BigDecimal.TEN).paymentMethod(PaymentMethod.CARD)
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            Itinerary it = itinerary("k", BookingStatus.CANCELLED,
                    List.of(leg(flightA, seatA, 0)));
            it.setCancellationIdempotencyKey("cancel-1");
            it.setPayment(charge);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            svc.cancel(it.getId(), alice.getId(), "cancel-1");

            verify(paymentService, never()).refund(any(), any());
            verify(flightSeatRepository, never()).deleteByFlight_IdAndSeat_Id(any(), any());
            verify(flightSeatRepository, never()).deleteAllByItinerary_Id(any());
        }

        @Test
        void cancelledDifferentKey_is409() {
            Itinerary it = itinerary("k", BookingStatus.CANCELLED,
                    List.of(leg(flightA, seatA, 0)));
            it.setCancellationIdempotencyKey("cancel-first");
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.cancel(it.getId(), alice.getId(), "cancel-different"))
                    .withMessageContaining("already CANCELLED");
        }

        @Test
        void nonOwnerCantCancel() {
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.cancel(it.getId(), 999L, "cancel-1"))
                    .withMessageContaining("not found for this user");
        }

        @Test
        void unknownItineraryIdYields404() {
            when(itineraryRepository.findByIdWithGraph(1L)).thenReturn(Optional.empty());
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.cancel(1L, alice.getId(), "cancel-1"));
        }

        @Test
        @DisplayName("cancel refuses if any leg has already departed (Fix 2)")
        void cancelWithAnyDepartedLegIsRejected() {
            // Two-leg itinerary. flightB (BLR->DEL) is in the past —
            // leg 0 has already flown, so the itinerary has been
            // partially consumed. Refuse the whole cancel; partial
            // refunds aren't supported.
            Flight departedLeg = Flight.builder().id(80L).flightModel(model)
                    .source("BLR").destination("DEL").fullyBooked(false)
                    .cost(new BigDecimal("3000"))
                    .startTime(Instant.now().minusSeconds(3600))
                    .endTime(Instant.now().minusSeconds(1800))
                    .build();
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(departedLeg, seatB, 0), leg(flightA, seatA, 1)));
            Payment charge = Payment.builder().id(100L).type(PaymentType.CHARGE)
                    .amount(new BigDecimal("6000")).paymentMethod(PaymentMethod.CARD)
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            it.setPayment(charge);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.cancel(it.getId(), alice.getId(), "cancel-late"))
                    .withMessageContaining("departed");

            // Nothing must be deleted, refunded, or notified — all
            // side effects gated by the check.
            verify(flightSeatRepository, never()).deleteByFlight_IdAndSeat_Id(any(), any());
            verify(flightSeatRepository, never()).deleteAllByItinerary_Id(any());
            verify(paymentService, never()).refund(any(), any());
            verify(waitlistService, never()).notifyAllWaitersOfOpening(any());
        }
    }

    // ================================================================
    // Get
    // ================================================================

    @Nested
    class GetItinerary {

        @Test
        void returnsFullyPopulatedDtoForOwner() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            BookingItineraryDto out = svc.getItinerary(it.getId(), alice.getId());

            assertThat(out.itineraryId()).isEqualTo(it.getId());
            assertThat(out.legs()).hasSize(1);
            assertThat(out.legs().get(0).seatNumber()).isEqualTo(seatA.getSeatNumber());
        }

        @Test
        void unknownIs404() {
            when(itineraryRepository.findByIdWithGraph(1L)).thenReturn(Optional.empty());
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.getItinerary(1L, alice.getId()));
        }

        @Test
        void nonOwnerGetsMaskedConflictNotAForbidden() {
            // IDOR guard: a caller who isn't the itinerary's owner
            // must get the same "not found for this user" message as
            // confirm / cancel, NOT a distinct 403 that would leak
            // "this id exists but belongs to someone else."
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.getItinerary(it.getId(), 999L))
                    .withMessageContaining("not found for this user");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private static ReserveRequest request(LegRequest... legs) {
        return new ReserveRequest(List.of(legs));
    }

    private static SeatOccupancyRow seatRow(Seat s, boolean booked) {
        // flightSeatId non-null encodes "booked"; null is "available".
        return new SeatOccupancyRow(s.getId(), s.getSeatNumber(), booked ? 999L : null);
    }

    /**
     * Count elements in an Iterable — a tiny helper that lets
     * saveAll verifications assert "N legs bulk-saved" without
     * pulling in AssertJ from an argument matcher lambda.
     */
    private static int countLegs(Iterable<?> it) {
        int n = 0;
        for (var ignored : it) n++;
        return n;
    }

    /** Build a small in-memory itinerary graph for the mock-repo returns. */
    private Itinerary itinerary(String key, BookingStatus status, List<LegBuild> legs) {
        Itinerary it = Itinerary.builder()
                .id(500L)
                .user(alice)
                .status(status)
                .idempotencyKey(key)
                .reservedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .finalPrice(new BigDecimal("3200"))
                .build();
        AtomicLong bookingId = new AtomicLong(600L);
        List<Booking> children = new ArrayList<>();
        for (LegBuild lb : legs) {
            Booking b = Booking.builder()
                    .id(bookingId.getAndIncrement())
                    .itinerary(it).legOrder(lb.order)
                    .flight(lb.flight).seat(lb.seat)
                    .finalPrice(new BigDecimal("3200"))
                    .build();
            children.add(b);
        }
        it.setLegs(children);
        return it;
    }

    private static LegBuild leg(Flight f, Seat s, int order) {
        return new LegBuild(f, s, order);
    }

    private record LegBuild(Flight flight, Seat seat, int order) {}

    // ---- arrangement helpers (mock stubs) -----------------------------

    private void arrangeMissedIdempotency(String key) {
        when(itineraryRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
    }

    private void arrangeUserLoad(User u) {
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
    }

    /**
     * Populate the shared {@link #stubbedFlightsById} map that
     * backs the lenient bulk-fetch stub installed in {@link #setUp}.
     * BookingService.reserve now issues one
     * {@code findAllByIdInWithFlightModel(ids)} per invocation
     * instead of N single-id lookups, so a per-flight
     * {@code when(...).thenReturn(...)} would only satisfy the
     * last-stubbed id. Accumulating into a map + answering off
     * the requested id set is the shape that matches the bulk
     * API without touching every test call-site.
     */
    private void arrangeFlight(Flight f) {
        stubbedFlightsById.put(f.getId(), f);
    }

    /**
     * Register the seat layout for one flight. Under the hood this
     * feeds the {@link #stubbedLayoutByFlightId} map that the
     * lenient {@code findSeatOccupancyForFlights} stub in
     * {@link #setUp} reads.
     *
     * <p>{@code modelId} is kept in the signature so existing
     * call-sites don't have to change; it's no longer used because
     * the bulk query pulls layout per flight_id and the seed row's
     * flightId is what BookingService groups by. Rows arriving via
     * {@code seatRow(seat, booked)} carry a null flightId; we
     * re-wrap them here so grouping under the new bulk shape
     * works.</p>
     */
    private void arrangeSeatLayout(Long flightId, Long modelId, SeatOccupancyRow... rows) {
        List<SeatOccupancyRow> withFlight = new ArrayList<>(rows.length);
        for (SeatOccupancyRow r : rows) {
            withFlight.add(new SeatOccupancyRow(
                    r.seatId(), r.seatNumber(), r.flightSeatId(), flightId));
        }
        stubbedLayoutByFlightId.put(flightId, withFlight);
    }

    private void arrangeSeatRef(Seat s) {
        when(seatRepository.getReferenceById(s.getId())).thenReturn(s);
    }

    private void arrangeQuote(Flight f, long booked, BigDecimal price) {
        List<PriceBreakdownEntry> breakdown = List.of(
                new PriceBreakdownEntry("base", price, "base fare"));
        when(flightPricingService.quoteFor(f, booked))
                .thenReturn(new PriceQuote(price, breakdown));
    }

    private void arrangeLockSucceeds() {
        when(seatLockService.tryLock(anyLong(), anyLong(), anyString(), any()))
                .thenReturn(true);
    }

    private void arrangeItinerarySave() {
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> {
            Itinerary it = inv.getArgument(0);
            if (it.getId() == null) it.setId(500L);
            return it;
        });
    }

    /**
     * BookingService.reserve now persists every leg in a single
     * {@code saveAll(...)} call instead of a per-leg
     * {@code save(...)} loop, so this stub mirrors that. Each row
     * is assigned an id in caller order to preserve leg_order
     * semantics (leg 0 gets 600, leg 1 gets 601, ...).
     */
    @SuppressWarnings("unchecked")
    private void arrangeBookingSave() {
        AtomicLong ids = new AtomicLong(600L);
        when(bookingRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<Booking> in = (Iterable<Booking>) inv.getArgument(0);
            List<Booking> out = new ArrayList<>();
            for (Booking b : in) {
                if (b.getId() == null) b.setId(ids.getAndIncrement());
                out.add(b);
            }
            return out;
        });
    }
}
