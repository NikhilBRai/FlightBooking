package com.flightbooking.service;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.entity.WaitlistEntry;
import com.flightbooking.exception.ResourceNotFoundException;
import com.flightbooking.repository.FlightRepository;
import com.flightbooking.repository.UserRepository;
import com.flightbooking.repository.WaitlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Manages the per-flight waitlist. When a seat opens up (via cancel),
 * every waiter is notified in FIFO order and left on the list — first
 * to grab a fresh reserve wins the Redis lock, the others get the
 * standard 409 and their waitlist entry is preserved so they'll be
 * notified again on the next opening. Users leave the waitlist
 * explicitly via {@link #removeFromWaitlist}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final WaitlistRepository waitlistRepository;
    private final FlightRepository flightRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * Join {@code flightId}'s waitlist as {@code userId}. Idempotent
     * — if the user is already on the waitlist we return the existing
     * row rather than 409, so a client that lost the response to the
     * first attempt can safely retry.
     *
     * <p>Holding an active booking on this flight does <b>not</b>
     * disqualify a user from also being on the waitlist. Since
     * {@code reserve} already lets one user hold multiple seats on
     * the same flight (family / group travel), the symmetric rule for
     * the waitlist is "notify me when another seat opens." Users who
     * don't want that can leave the waitlist explicitly via
     * {@link #removeFromWaitlist}.</p>
     */
    @Transactional
    public WaitlistEntry addToWaitlist(Long userId, Long flightId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        Flight flight = flightRepository.findById(flightId)
                .orElseThrow(() -> new ResourceNotFoundException("Flight not found: " + flightId));

        Optional<WaitlistEntry> existing =
                waitlistRepository.findByFlight_IdAndUser_Id(flightId, userId);
        if (existing.isPresent()) {
            log.info("Waitlist idempotency hit: userId={} flightId={} entryId={}",
                    userId, flightId, existing.get().getId());
            return existing.get();
        }

        WaitlistEntry entry = waitlistRepository.save(WaitlistEntry.builder()
                .user(user)
                .flight(flight)
                .addedAt(Instant.now())
                .build());
        log.info("Waitlist joined: userId={} flightId={} entryId={}",
                userId, flightId, entry.getId());
        return entry;
    }

    /**
     * Leave {@code flightId}'s waitlist. Idempotent — calling it when
     * you're not on the waitlist is a no-op, and the endpoint returns
     * {@code 204 No Content} either way. Callers use this to opt out
     * of further openings-available notifications.
     */
    @Transactional
    public void removeFromWaitlist(Long userId, Long flightId) {
        long removed = waitlistRepository.deleteByFlight_IdAndUser_Id(flightId, userId);
        log.info("Waitlist leave: userId={} flightId={} removed={}", userId, flightId, removed);
    }

    /**
     * Fan out a "a seat just opened" notification to <em>every</em>
     * waiter on {@code flight}'s list, in FIFO join order. Entries
     * are <b>not</b> deleted — a notified user still has to race a
     * fresh reserve for the seat, and if they lose the race we want
     * them to be told about the next opening too.
     *
     * <p>Called from {@link BookingService#cancel} inside the same
     * transaction. That's fine while notifications are a synchronous
     * log stub, but is a known scale limit: with a real notification
     * bus and N waiters this puts N gateway calls inside a DB tx.
     * The follow-up is to fire an event after commit and let an
     * out-of-band worker do the fan-out.</p>
     */
    @Transactional
    public void notifyAllWaitersOfOpening(Flight flight) {
        List<WaitlistEntry> waiters =
                waitlistRepository.findByFlight_IdOrderByAddedAtAsc(flight.getId());
        if (waiters.isEmpty()) {
            return;
        }
        String subject = "Seat available on flight " + flight.getId();
        String body = "A seat just opened up on " + flight.getSource()
                + " -> " + flight.getDestination() + ". Book quickly!";
        for (WaitlistEntry entry : waiters) {
            notificationService.notifyUser(entry.getUser(), subject, body);
        }
        log.info("Notified {} waiter(s) about opening on flightId={}",
                waiters.size(), flight.getId());
    }
}
