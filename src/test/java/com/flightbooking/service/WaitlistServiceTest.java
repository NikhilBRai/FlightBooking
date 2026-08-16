package com.flightbooking.service;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.entity.WaitlistEntry;
import com.flightbooking.exception.InvalidBookingStateException;
import com.flightbooking.exception.ResourceNotFoundException;
import com.flightbooking.repository.FlightRepository;
import com.flightbooking.repository.UserRepository;
import com.flightbooking.repository.WaitlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock WaitlistRepository waitlistRepository;
    @Mock FlightRepository flightRepository;
    @Mock UserRepository userRepository;
    @Mock NotificationService notificationService;

    @InjectMocks WaitlistService svc;

    private User alice;
    private Flight flight;

    @BeforeEach
    void setUp() {
        alice = User.builder().id(1L).name("Alice").email("alice@example.com").build();
        flight = Flight.builder().id(10L)
                .flightModel(FlightModel.builder().id(1L).totalSeats(6).make("test").build())
                .source("BLR").destination("BOM")
                .cost(new BigDecimal("1000"))
                .startTime(Instant.parse("2030-01-01T00:00:00Z"))
                .endTime(Instant.parse("2030-01-01T02:00:00Z"))
                .build();
    }

    // ---- addToWaitlist ------------------------------------------------

    @Test
    void addToWaitlist_persistsNewEntryWhenAllChecksPass() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(flightRepository.findById(10L)).thenReturn(Optional.of(flight));
        when(waitlistRepository.findByFlight_IdAndUser_Id(10L, 1L)).thenReturn(Optional.empty());
        when(waitlistRepository.save(any(WaitlistEntry.class))).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setId(555L);
            return e;
        });

        WaitlistEntry out = svc.addToWaitlist(1L, 10L);

        ArgumentCaptor<WaitlistEntry> captor = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(waitlistRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(alice);
        assertThat(captor.getValue().getFlight()).isSameAs(flight);
        assertThat(captor.getValue().getAddedAt()).isNotNull();
        assertThat(out.getId()).isEqualTo(555L);
    }

    @Test
    void addToWaitlist_isIdempotent_returnsExistingRowWithoutInsert() {
        WaitlistEntry existing = WaitlistEntry.builder().id(42L).user(alice).flight(flight)
                .addedAt(Instant.parse("2030-01-01T00:00:00Z")).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(flightRepository.findById(10L)).thenReturn(Optional.of(flight));
        when(waitlistRepository.findByFlight_IdAndUser_Id(10L, 1L)).thenReturn(Optional.of(existing));

        WaitlistEntry out = svc.addToWaitlist(1L, 10L);

        assertThat(out).isSameAs(existing);
        verify(waitlistRepository, never()).save(any(WaitlistEntry.class));
    }

    @Test
    void addToWaitlist_holdingActiveBookingDoesNotBlockJoin() {
        // Policy: holding a seat on this flight is not a disqualifier
        // for the waitlist. reserve() already allows the same user to
        // hold multiple seats on one flight (family / group travel);
        // the symmetric rule for the waitlist is "notify me when
        // another seat opens". The only per-user+per-flight guard
        // that stays is the idempotency check on the waitlist row
        // itself, exercised in addToWaitlist_isIdempotent_...
        //
        // This test locks that policy in — the service must not call
        // any active-booking-detection query and must save the entry
        // regardless of any pre-existing booking the user has.
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(flightRepository.findById(10L)).thenReturn(Optional.of(flight));
        when(waitlistRepository.findByFlight_IdAndUser_Id(10L, 1L)).thenReturn(Optional.empty());
        when(waitlistRepository.save(any(WaitlistEntry.class))).thenAnswer(inv -> {
            WaitlistEntry e = inv.getArgument(0);
            e.setId(777L);
            return e;
        });

        WaitlistEntry out = svc.addToWaitlist(1L, 10L);

        assertThat(out.getId()).isEqualTo(777L);
        verify(waitlistRepository, times(1)).save(any(WaitlistEntry.class));
    }

    @Test
    void addToWaitlist_unknownUserYields404() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> svc.addToWaitlist(1L, 10L))
                .withMessageContaining("User");
        verify(flightRepository, never()).findById(any());
    }

    @Test
    void addToWaitlist_unknownFlightYields404() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(flightRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class)
                .isThrownBy(() -> svc.addToWaitlist(1L, 10L))
                .withMessageContaining("Flight");
    }

    @Test
    void addToWaitlist_refusesJoinAfterFlightDeparted() {
        // Waitlist notifications fire on OTHER users' cancellations
        // — cancel is itself refused for departed flights (see
        // BookingServiceTest.Cancel), so a waitlist entry against
        // a departed flight would never trigger. Refuse the join
        // early rather than silently persist a dead row.
        Flight departed = Flight.builder().id(11L)
                .flightModel(flight.getFlightModel())
                .source("BLR").destination("BOM")
                .cost(new BigDecimal("1000"))
                .startTime(Instant.now().minusSeconds(60))
                .endTime(Instant.now().plusSeconds(60))
                .build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(flightRepository.findById(11L)).thenReturn(Optional.of(departed));

        assertThatExceptionOfType(InvalidBookingStateException.class)
                .isThrownBy(() -> svc.addToWaitlist(1L, 11L))
                .withMessageContaining("departed");

        verify(waitlistRepository, never()).save(any(WaitlistEntry.class));
    }

    // ---- removeFromWaitlist ------------------------------------------

    @Test
    void removeFromWaitlist_delegatesToRepositoryDelete() {
        when(waitlistRepository.deleteByFlight_IdAndUser_Id(10L, 1L)).thenReturn(1L);
        svc.removeFromWaitlist(1L, 10L);
        verify(waitlistRepository).deleteByFlight_IdAndUser_Id(10L, 1L);
    }

    @Test
    void removeFromWaitlist_noRowIsStillFine_returnZeroCount() {
        when(waitlistRepository.deleteByFlight_IdAndUser_Id(10L, 1L)).thenReturn(0L);
        // Should not throw — idempotent by design.
        svc.removeFromWaitlist(1L, 10L);
        verify(waitlistRepository).deleteByFlight_IdAndUser_Id(10L, 1L);
    }

    // ---- notifyAllWaitersOfOpening ------------------------------------

    @Test
    void notifyAllWaitersOfOpening_notifiesEveryWaiterInFifoOrder() {
        User bob = User.builder().id(2L).name("Bob").email("b@e").build();
        User carol = User.builder().id(3L).name("Carol").email("c@e").build();
        WaitlistEntry first = WaitlistEntry.builder().id(1L).user(bob).flight(flight)
                .addedAt(Instant.parse("2030-01-01T00:00:00Z")).build();
        WaitlistEntry second = WaitlistEntry.builder().id(2L).user(carol).flight(flight)
                .addedAt(Instant.parse("2030-01-01T00:00:01Z")).build();
        when(waitlistRepository.findByFlight_IdOrderByAddedAtAsc(10L)).thenReturn(List.of(first, second));

        svc.notifyAllWaitersOfOpening(flight);

        verify(notificationService).notifyUser(eq(bob), any(), any());
        verify(notificationService).notifyUser(eq(carol), any(), any());
        verify(notificationService, times(2)).notifyUser(any(User.class), any(), any());
        verify(waitlistRepository, never()).delete(any());
        verify(waitlistRepository, never()).deleteByFlight_IdAndUser_Id(any(), any());
    }

    @Test
    void notifyAllWaitersOfOpening_isNoOpWhenWaitlistEmpty() {
        when(waitlistRepository.findByFlight_IdOrderByAddedAtAsc(10L)).thenReturn(List.of());

        svc.notifyAllWaitersOfOpening(flight);

        verify(notificationService, never()).notifyUser(any(), any(), any());
    }

}
