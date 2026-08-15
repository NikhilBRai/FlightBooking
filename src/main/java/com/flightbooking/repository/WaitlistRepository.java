package com.flightbooking.repository;

import com.flightbooking.domain.entity.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    /**
     * All waiters on this flight in FIFO order, with each waiter's
     * {@code user} loaded alongside so the fan-out loop in
     * {@code WaitlistService.notifyAllWaitersOfOpening} can read
     * {@code user.name} / {@code user.email} without a per-waiter
     * round-trip.
     */
    @Query("""
        SELECT w
          FROM WaitlistEntry w
          JOIN FETCH w.user
         WHERE w.flight.id = :flightId
         ORDER BY w.addedAt ASC
        """)
    List<WaitlistEntry> findByFlight_IdOrderByAddedAtAsc(@Param("flightId") Long flightId);

    /** Membership check that powers the idempotent add. */
    Optional<WaitlistEntry> findByFlight_IdAndUser_Id(Long flightId, Long userId);

    boolean existsByFlight_IdAndUser_Id(Long flightId, Long userId);

    /**
     * Idempotent leave — returns the number of rows removed (0 if the
     * user wasn't on the waitlist). Callers should treat both 0 and 1
     * as success and respond with {@code 204 No Content}.
     */
    @Modifying
    long deleteByFlight_IdAndUser_Id(Long flightId, Long userId);
}
