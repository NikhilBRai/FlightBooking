package com.flightbooking.repository;

import com.flightbooking.domain.entity.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every persisted flight is a direct point-to-point segment. Multi-stop
 * itineraries (up to {@code app.search.max-stops-cap}) are stitched together
 * at search time via a <b>bounded backward expansion</b> starting from
 * flights arriving at the user's destination.
 *
 * <p>Three queries participate:</p>
 * <ol>
 *   <li>{@link #findInboundInWindow} — the <b>spine</b>: every flight
 *       landing at the user's destination whose departure is in the day
 *       window. Rows whose source equals the user's origin are direct
 *       itineraries; others are candidate last legs of multi-hop trips.</li>
 *   <li>{@link #findLandingAtHubInWindow} — an <b>intermediate hop</b>:
 *       every flight (from any source) landing at a given hub inside the
 *       layover window. Used when there's room in the depth budget to
 *       recurse further backward looking for a longer path.</li>
 *   <li>{@link #findFeederLegs} — the <b>deepest hop</b>: only feeders from
 *       the user's actual source into the hub. Used as an optimization at
 *       the last allowed backward step, when only a userSource-rooted feeder
 *       can close the itinerary anyway.</li>
 * </ol>
 *
 * <p>All three exclude {@code fullyBooked = true} — search never surfaces a
 * segment the user couldn't actually book.</p>
 */
public interface FlightRepository extends JpaRepository<Flight, Long> {

    @Query("""
        SELECT f
          FROM Flight f
         WHERE f.destination = :destination
           AND f.startTime >= :windowStart
           AND f.startTime <  :windowEnd
           AND f.fullyBooked = false
        """)
    List<Flight> findInboundInWindow(@Param("destination") String destination,
                                     @Param("windowStart") Instant windowStart,
                                     @Param("windowEnd") Instant windowEnd);

    /**
     * All flights landing at {@code hub} inside the layover window
     * {@code [earliestLanding, latestLanding]}. Used during intermediate
     * hops of the backward expansion, so no source filter — any airport
     * that could act as a preceding hub is a candidate.
     */
    @Query("""
        SELECT f
          FROM Flight f
         WHERE f.destination = :hub
           AND f.endTime >= :earliestLanding
           AND f.endTime <= :latestLanding
           AND f.fullyBooked = false
        """)
    List<Flight> findLandingAtHubInWindow(@Param("hub") String hub,
                                          @Param("earliestLanding") Instant earliestLanding,
                                          @Param("latestLanding") Instant latestLanding);

    /**
     * Strict feeders — only {@code userSource → hub}. Used as an
     * optimization at the deepest allowed backward hop, when only a
     * feeder rooted at the user's source can close the itinerary.
     */
    @Query("""
        SELECT f
          FROM Flight f
         WHERE f.source = :userSource
           AND f.destination = :hub
           AND f.endTime >= :earliestLanding
           AND f.endTime <= :latestLanding
           AND f.fullyBooked = false
        """)
    List<Flight> findFeederLegs(@Param("userSource") String userSource,
                                @Param("hub") String hub,
                                @Param("earliestLanding") Instant earliestLanding,
                                @Param("latestLanding") Instant latestLanding);

    /**
     * The whole candidate <b>pool</b> for a batched search strategy —
     * every flight (any source, any destination) whose landing time
     * falls in {@code [earliestLanding, latestLanding)} and that is
     * not fully booked. The batched strategy calls this exactly once
     * per user search, then bucketises the result by destination
     * airport and drives the entire backward expansion off the map,
     * so no further DB round-trips are needed regardless of how many
     * hubs or hops the recursion visits.
     *
     * <p>{@code latestLanding} is exclusive on purpose: it lines up
     * with the exclusive {@code windowEnd} used for the spine query
     * ({@code date + 1 day}), so the two together cover every
     * flight that could belong to an itinerary departing on
     * {@code date}.</p>
     */
    @Query("""
        SELECT f
          FROM Flight f
         WHERE f.endTime >= :earliestLanding
           AND f.endTime <  :latestLanding
           AND f.fullyBooked = false
        """)
    List<Flight> findAllLandingInWindow(@Param("earliestLanding") Instant earliestLanding,
                                        @Param("latestLanding") Instant latestLanding);

    /**
     * Loads a flight together with its {@code FlightModel} in a
     * single query. Prefer this over {@code findById} whenever the
     * caller will read the aircraft data (pricing needs
     * {@code totalSeats}, details/DTO need {@code make}).
     */
    @Query("""
        SELECT f
          FROM Flight f
          JOIN FETCH f.flightModel
         WHERE f.id = :id
        """)
    Optional<Flight> findByIdWithFlightModel(@Param("id") Long id);
}
