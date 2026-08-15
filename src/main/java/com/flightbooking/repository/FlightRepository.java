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
 *
 * <p>Every search-time query {@code JOIN FETCH}es {@code flightModel}
 * because both downstream steps — {@code FlightPricingService.quoteForAll}
 * (reads {@code totalSeats}) and {@code FlightService.toSummary} (reads
 * {@code make}) — dereference the association per unique flight. Without
 * the fetch, each returned flight triggers one extra {@code SELECT} on
 * {@code flight_models} at pricing/DTO-mapping time, turning "two DB
 * round-trips per search" into two + N. The join is cheap because
 * {@code flight_model_id} is {@code NOT NULL} (INNER JOIN drops no rows)
 * and {@code ManyToOne} fetches don't multiply rows (no {@code DISTINCT}
 * needed).</p>
 */
public interface FlightRepository extends JpaRepository<Flight, Long> {

    @Query("""
        SELECT f
          FROM Flight f
          JOIN FETCH f.flightModel
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
          JOIN FETCH f.flightModel
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
          JOIN FETCH f.flightModel
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
     * falls in the closed interval {@code [earliestLanding, latestLanding]}
     * and that is not fully booked. The batched strategy calls this
     * exactly once per user search, then bucketises the result by
     * destination airport and drives the entire backward expansion
     * off the map, so no further DB round-trips are needed regardless
     * of how many hubs or hops the recursion visits.
     *
     * <p><b>Both bounds are inclusive</b>: a feeder landing exactly at
     * {@code earliestLanding} could still chain into a spine via
     * back-to-back max-length layovers, and a feeder landing exactly
     * at {@code latestLanding} can still feed the latest spine at
     * exactly {@code minLayover} before its departure. Matches the
     * per-path filter in {@code BatchedFlightSearchStrategy}, which
     * uses {@code isAfter}/{@code isBefore} (strict {@code >}/{@code <}).</p>
     *
     * <p>Unlike the other three, this query has no destination filter,
     * so the destination-prefixed indexes on {@code flights} don't
     * help. {@code idx_flights_end_time} on the entity backs it.</p>
     */
    @Query("""
        SELECT f
          FROM Flight f
          JOIN FETCH f.flightModel
         WHERE f.endTime >= :earliestLanding
           AND f.endTime <= :latestLanding
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
