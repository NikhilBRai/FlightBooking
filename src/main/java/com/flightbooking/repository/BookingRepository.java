package com.flightbooking.repository;

import com.flightbooking.domain.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Legs are almost never fetched independently — the booking flow
 * goes through {@link ItineraryRepository#findByIdWithGraph} which
 * pulls the whole itinerary and its legs in one shot. This
 * repository stays around only for a couple of narrow leg-scoped
 * queries (waitlist promotion candidates, integration test
 * fixtures) that don't need the parent itinerary loaded.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Every leg on {@code flightId} regardless of itinerary status.
     * Used by tests and admin queries; not on the hot path of any
     * user-facing API.
     */
    List<Booking> findByFlight_Id(Long flightId);
}
