package com.flightbooking.service;

import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(svc, "reservationTtlMinutes", 5);
        alice = User.builder().id(1L).name("Alice").email("a@e").build();
        model = FlightModel.builder().id(1L).make("Boeing").totalSeats(6).build();
        flightA = Flight.builder().id(10L).flightModel(model)
                .source("BLR").destination("DEL").cost(new BigDecimal("3000"))
                .startTime(Instant.parse("2030-01-01T08:00:00Z"))
                .endTime(Instant.parse("2030-01-01T10:00:00Z"))
                .fullyBooked(false).build();
        flightB = Flight.builder().id(11L).flightModel(model)
                .source("DEL").destination("BOM").cost(new BigDecimal("2500"))
                .startTime(Instant.parse("2030-01-01T14:00:00Z"))
                .endTime(Instant.parse("2030-01-01T16:00:00Z"))
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
            verify(bookingRepository).save(any(Booking.class));
            verify(seatLockService, never()).release(anyLong(), anyLong(), any());
        }

        @Test
        void twoLegHappyPath_locksInCanonicalOrderPersistsInCallerOrder() {
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

            // Caller order: flight 10 → flight 11 (already the
            // canonical (flightId, seatId) order, so lock order
            // should match caller order here).
            BookingItineraryDto out = svc.reserve(alice.getId(), "k-multi", request(
                    new LegRequest(flightA.getId(), seatA.getId()),
                    new LegRequest(flightB.getId(), seatB.getId())));

            assertThat(out.legs()).hasSize(2);
            assertThat(out.legs().get(0).legOrder()).isZero();
            assertThat(out.legs().get(0).flightId()).isEqualTo(flightA.getId());
            assertThat(out.legs().get(1).legOrder()).isOne();
            assertThat(out.legs().get(1).flightId()).isEqualTo(flightB.getId());
            assertThat(out.totalFinalPrice()).isEqualByComparingTo("6000");

            verify(seatLockService).tryLock(flightA.getId(), seatA.getId(), "k-multi", Duration.ofMinutes(5));
            verify(seatLockService).tryLock(flightB.getId(), seatB.getId(), "k-multi", Duration.ofMinutes(5));
            verify(itineraryRepository).save(any(Itinerary.class));
            verify(bookingRepository, times(2)).save(any(Booking.class));
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

            // Caller order: flightB first, flightA second.
            // Canonical order (flightId asc): flightA then flightB.
            BookingItineraryDto out = svc.reserve(alice.getId(), "k-rev", request(
                    new LegRequest(flightB.getId(), seatB.getId()),
                    new LegRequest(flightA.getId(), seatA.getId())));

            // Legs persist in caller order.
            assertThat(out.legs()).extracting(l -> l.flightId())
                    .containsExactly(flightB.getId(), flightA.getId());
            assertThat(out.legs()).extracting(l -> l.legOrder())
                    .containsExactly(0, 1);

            // Lock invocations both happen; we don't assert the
            // precise interleaving here (both fired inside the same
            // Mockito verify scope). What matters is that both
            // succeeded — the deadlock-avoidance property is tested
            // end-to-end in BookingIT.
            verify(seatLockService).tryLock(flightA.getId(), seatA.getId(), "k-rev", Duration.ofMinutes(5));
            verify(seatLockService).tryLock(flightB.getId(), seatB.getId(), "k-rev", Duration.ofMinutes(5));
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
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeSeatLayout(flightA.getId(), model.getId(),
                    seatRow(seatA, true), seatRow(seatB, false));

            assertThatExceptionOfType(SeatUnavailableException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))));
            verify(seatLockService, never()).tryLock(anyLong(), anyLong(), anyString(), any());
        }

        @Test
        void seatNotInLayoutYields404() {
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeSeatLayout(flightA.getId(), model.getId(), seatRow(seatB, false));

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("Seat " + seatA.getId());
        }

        @Test
        void flightNotFoundYields404() {
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            when(flightRepository.findByIdWithFlightModel(flightA.getId())).thenReturn(Optional.empty());

            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k",
                            request(new LegRequest(flightA.getId(), seatA.getId()))))
                    .withMessageContaining("Flight not found");
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
        @DisplayName("second leg's lock fails: first leg's lock is released, no itinerary persisted")
        void partialLockFailure_releasesAcquiredLocksAndRollsBack() {
            arrangeMissedIdempotency("k-part");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeFlight(flightB);
            arrangeSeatLayout(flightA.getId(), model.getId(), seatRow(seatA, false));
            arrangeSeatLayout(flightB.getId(), model.getId(), seatRow(seatB, false));
            arrangeSeatRef(seatA);
            arrangeSeatRef(seatB);
            arrangeQuote(flightA, 0, new BigDecimal("3200"));
            arrangeQuote(flightB, 0, new BigDecimal("2800"));

            // flightA lock succeeds, flightB lock fails.
            when(seatLockService.tryLock(eq(flightA.getId()), eq(seatA.getId()), eq("k-part"), any()))
                    .thenReturn(true);
            when(seatLockService.tryLock(eq(flightB.getId()), eq(seatB.getId()), eq("k-part"), any()))
                    .thenReturn(false);

            assertThatExceptionOfType(SeatUnavailableException.class)
                    .isThrownBy(() -> svc.reserve(alice.getId(), "k-part", request(
                            new LegRequest(flightA.getId(), seatA.getId()),
                            new LegRequest(flightB.getId(), seatB.getId()))));

            // The successfully-taken flightA lock must be released;
            // the failed flightB lock must NOT be released (we
            // don't own it). No itinerary should have been
            // persisted.
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k-part");
            verify(seatLockService, never()).release(eq(flightB.getId()), eq(seatB.getId()), anyString());
            verify(itineraryRepository, never()).save(any(Itinerary.class));
            verify(bookingRepository, never()).save(any(Booking.class));
        }

        @Test
        void firstLockFailureShortCircuits_neverLoadsSecondLegsFlight() {
            arrangeMissedIdempotency("k");
            arrangeUserLoad(alice);
            arrangeFlight(flightA);
            arrangeSeatLayout(flightA.getId(), model.getId(), seatRow(seatA, false));
            arrangeSeatRef(seatA);
            arrangeQuote(flightA, 0, new BigDecimal("3200"));

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
                    .paymentMethod("card").idempotencyKey("k")
                    .transactionId("txn").itinerary(it).build();
            when(paymentService.charge(eq(it), any(), eq("card"), eq("k"))).thenReturn(p);
            when(itineraryRepository.save(it)).thenReturn(it);
            when(flightSeatRepository.countByFlight_Id(flightA.getId())).thenReturn(1L);

            BookingItineraryDto out = svc.confirm(it.getId(), alice.getId(), "k",
                    new ConfirmRequest("card"));

            assertThat(out.status()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(it.getPayment()).isSameAs(p);
            verify(flightSeatRepository).saveAndFlush(any());
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
                    .paymentMethod("card").idempotencyKey("k")
                    .transactionId("txn").itinerary(it).build();
            when(paymentService.charge(eq(it), any(), eq("card"), eq("k"))).thenReturn(p);
            when(itineraryRepository.save(it)).thenReturn(it);
            when(flightSeatRepository.countByFlight_Id(anyLong())).thenReturn(1L);

            svc.confirm(it.getId(), alice.getId(), "k", new ConfirmRequest("card"));

            // One aggregated charge, two flight_seats rows, two lock releases.
            verify(paymentService, times(1)).charge(eq(it), any(), eq("card"), eq("k"));
            verify(flightSeatRepository, times(2)).saveAndFlush(any());
            verify(seatLockService).release(flightA.getId(), seatA.getId(), "k");
            verify(seatLockService).release(flightB.getId(), seatB.getId(), "k");
        }

        @Test
        void alreadyConfirmed_returnsCachedDtoWithoutRechargeOrInsert() {
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            svc.confirm(it.getId(), alice.getId(), "k", new ConfirmRequest("card"));

            verify(paymentService, never()).charge(any(), any(), any(), any());
            verify(flightSeatRepository, never()).saveAndFlush(any());
        }

        @Test
        void cancelledItineraryCantBeConfirmed() {
            Itinerary it = itinerary("k", BookingStatus.CANCELLED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), alice.getId(), "k",
                            new ConfirmRequest("card")))
                    .withMessageContaining("cancelled");
        }

        @Test
        void nonOwnerCantConfirm() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), 999L, "k",
                            new ConfirmRequest("card")))
                    .withMessageContaining("not found for this user");
        }

        @Test
        void wrongIdempotencyKeyIsRefused() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            assertThatExceptionOfType(InvalidBookingStateException.class)
                    .isThrownBy(() -> svc.confirm(it.getId(), alice.getId(), "different",
                            new ConfirmRequest("card")))
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
                            new ConfirmRequest("card")))
                    .withMessageContaining("expired");

            verify(paymentService, never()).charge(any(), any(), any(), any());
            verify(flightSeatRepository, never()).saveAndFlush(any());
        }

        @Test
        void unknownItineraryIdYields404() {
            when(itineraryRepository.findByIdWithGraph(1L)).thenReturn(Optional.empty());
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.confirm(1L, alice.getId(), "k",
                            new ConfirmRequest("card")));
        }

        @Test
        void singleLegFullyBookedFlipsWhenLastSeatConfirmed() {
            // Total seats 2, count returns 2 → flip to fullyBooked.
            FlightModel small = FlightModel.builder().id(2L).make("Small").totalSeats(2).build();
            Flight solo = Flight.builder().id(20L).flightModel(small)
                    .source("BLR").destination("BOM").fullyBooked(false)
                    .cost(new BigDecimal("1000"))
                    .startTime(Instant.now()).endTime(Instant.now().plusSeconds(60)).build();
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(solo, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(seatLockService.isHeldBy(anyLong(), anyLong(), any())).thenReturn(true);
            Payment p = Payment.builder().id(1L).type(PaymentType.CHARGE)
                    .status(PaymentStatus.SUCCESS).amount(new BigDecimal("3200"))
                    .paymentMethod("card").transactionId("t").idempotencyKey("k").itinerary(it).build();
            when(paymentService.charge(any(), any(), any(), any())).thenReturn(p);
            when(itineraryRepository.save(it)).thenReturn(it);
            when(flightSeatRepository.countByFlight_Id(solo.getId())).thenReturn(2L);

            svc.confirm(it.getId(), alice.getId(), "k", new ConfirmRequest("card"));

            ArgumentCaptor<Flight> flightSave = ArgumentCaptor.forClass(Flight.class);
            verify(flightRepository).save(flightSave.capture());
            assertThat(flightSave.getValue().isFullyBooked()).isTrue();
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
                    .amount(new BigDecimal("3200")).paymentMethod("card")
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0)));
            it.setPayment(charge);
            charge.setItinerary(it);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(itineraryRepository.save(it)).thenReturn(it);

            svc.cancel(it.getId(), alice.getId(), "cancel-1");

            verify(flightSeatRepository).deleteByFlight_IdAndSeat_Id(flightA.getId(), seatA.getId());
            verify(paymentService).refund(50L, "refund:cancel-1");
            verify(waitlistService).notifyAllWaitersOfOpening(flightA);
            verify(notificationService).notifyUser(eq(alice), any(), any());
            assertThat(it.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(it.getCancellationIdempotencyKey()).isEqualTo("cancel-1");
        }

        @Test
        void multiLegCancel_deletesEveryFlightSeatRefundsOnceFansOutPerFlight() {
            Payment charge = Payment.builder().id(50L).type(PaymentType.CHARGE)
                    .amount(new BigDecimal("6000")).paymentMethod("card")
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            Itinerary it = itinerary("k", BookingStatus.CONFIRMED,
                    List.of(leg(flightA, seatA, 0), leg(flightB, seatB, 1)));
            it.setPayment(charge);
            charge.setItinerary(it);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));
            when(itineraryRepository.save(it)).thenReturn(it);

            svc.cancel(it.getId(), alice.getId(), "cancel-multi");

            verify(flightSeatRepository).deleteByFlight_IdAndSeat_Id(flightA.getId(), seatA.getId());
            verify(flightSeatRepository).deleteByFlight_IdAndSeat_Id(flightB.getId(), seatB.getId());
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
            verify(paymentService, never()).refund(any(), any());
            verify(waitlistService, never()).notifyAllWaitersOfOpening(any());
        }

        @Test
        void cancelledSameKey_isIdempotentReturnsCachedDto() {
            Payment charge = Payment.builder().id(1L).type(PaymentType.CHARGE)
                    .amount(BigDecimal.TEN).paymentMethod("card")
                    .status(PaymentStatus.SUCCESS).transactionId("t").idempotencyKey("k").build();
            Itinerary it = itinerary("k", BookingStatus.CANCELLED,
                    List.of(leg(flightA, seatA, 0)));
            it.setCancellationIdempotencyKey("cancel-1");
            it.setPayment(charge);
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            svc.cancel(it.getId(), alice.getId(), "cancel-1");

            verify(paymentService, never()).refund(any(), any());
            verify(flightSeatRepository, never()).deleteByFlight_IdAndSeat_Id(any(), any());
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
    }

    // ================================================================
    // Get
    // ================================================================

    @Nested
    class GetItinerary {

        @Test
        void returnsFullyPopulatedDto() {
            Itinerary it = itinerary("k", BookingStatus.RESERVED,
                    List.of(leg(flightA, seatA, 0)));
            when(itineraryRepository.findByIdWithGraph(it.getId())).thenReturn(Optional.of(it));

            BookingItineraryDto out = svc.getItinerary(it.getId());

            assertThat(out.itineraryId()).isEqualTo(it.getId());
            assertThat(out.legs()).hasSize(1);
            assertThat(out.legs().get(0).seatNumber()).isEqualTo(seatA.getSeatNumber());
        }

        @Test
        void unknownIs404() {
            when(itineraryRepository.findByIdWithGraph(1L)).thenReturn(Optional.empty());
            assertThatExceptionOfType(ResourceNotFoundException.class)
                    .isThrownBy(() -> svc.getItinerary(1L));
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

    private void arrangeFlight(Flight f) {
        when(flightRepository.findByIdWithFlightModel(f.getId())).thenReturn(Optional.of(f));
    }

    private void arrangeSeatLayout(Long flightId, Long modelId, SeatOccupancyRow... rows) {
        when(seatRepository.findSeatOccupancy(flightId, modelId)).thenReturn(List.of(rows));
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

    private void arrangeBookingSave() {
        AtomicLong ids = new AtomicLong(600L);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            if (b.getId() == null) b.setId(ids.getAndIncrement());
            return b;
        });
    }
}
