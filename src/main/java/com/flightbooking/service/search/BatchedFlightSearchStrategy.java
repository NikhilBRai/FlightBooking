package com.flightbooking.service.search;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.repository.FlightRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two-query search: one for the spine, one for the entire pool of
 * potentially-useful feeder flights. Every subsequent lookup during
 * backward expansion is an {@code O(1)} map read against a hub-indexed
 * bucket rather than a fresh DB round-trip.
 *
 * <p><b>Why this is safe.</b> A valid itinerary of at most
 * {@code effectiveMaxStops} stops departing from {@code source} on
 * {@code date} consists of at most {@code (effectiveMaxStops + 1)}
 * flights. Every non-spine flight in such an itinerary lands
 * {@code layover >= min-layover-minutes} before some later flight's
 * departure, and {@code layover <= max-layover-hours} at each hop. So
 * the earliest instant any feeder flight can land is:
 *
 * <pre>
 *   min(spine.departure) − effectiveMaxStops × max-layover-hours
 * </pre>
 *
 * <p>Any flight landing before that instant literally cannot be part
 * of any valid itinerary — even chaining maximum-length layovers
 * back-to-back can't stretch the trip that far into the past. The
 * pool query fetches {@code [earliestPossibleLanding, windowEnd)} in
 * one shot, which is a tight superset of every candidate.</p>
 *
 * <p><b>Trade-off vs {@link RecursiveFlightSearchStrategy}.</b> Query
 * count drops from {@code O(inbound × hubs × candidates_per_hub)} to
 * a flat two, at the cost of pulling every landing in the pool window
 * into memory once. For a bounded day-window this is a solid win
 * (spine + pool is a few hundred rows at most in this codebase).
 * Once the flight table dwarfs one day of RAM, the level-BFS variant
 * of this strategy (one query per depth level, scoped to the actual
 * frontier hubs) is the next stop — same core idea, less over-fetch.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.strategy", havingValue = "batched", matchIfMissing = true)
public class BatchedFlightSearchStrategy implements FlightSearchStrategy {

    private final FlightRepository flightRepository;

    @Value("${app.search.min-layover-minutes:60}")
    private long minLayoverMinutes;

    @Value("${app.search.max-layover-hours:12}")
    private long maxLayoverHours;

    @Override
    public String name() {
        return "batched";
    }

    @Override
    public List<List<Flight>> findPaths(String source, String destination,
                                        LocalDate date, int effectiveMaxStops) {
        Instant windowStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = windowStart.plus(Duration.ofDays(1));

        List<Flight> inbound = flightRepository.findInboundInWindow(destination, windowStart, windowEnd);
        if (inbound.isEmpty()) return List.of();

        Duration minLayover = Duration.ofMinutes(minLayoverMinutes);
        Duration maxLayover = Duration.ofHours(maxLayoverHours);

        List<List<Flight>> completedPaths = new ArrayList<>();

        // Fast path: any spine flight rooted at userSource is already
        // a direct itinerary. Do that first so we can bail early when
        // multi-hop is disabled (maxStops=0) without touching the pool
        // query at all.
        Instant earliestSpineDeparture = null;
        for (Flight spine : inbound) {
            if (spine.getSource().equals(source)) {
                completedPaths.add(List.of(spine));
            } else if (effectiveMaxStops >= 1) {
                // Only track spine departures we might actually expand
                // backward from — pure-direct results don't influence
                // the pool bound.
                if (earliestSpineDeparture == null
                        || spine.getStartTime().isBefore(earliestSpineDeparture)) {
                    earliestSpineDeparture = spine.getStartTime();
                }
            }
        }

        // Either maxStops=0, or every inbound flight is direct from
        // userSource. Nothing to expand — done.
        if (earliestSpineDeparture == null) {
            return completedPaths;
        }

        // Single pool fetch. See class Javadoc for why this bound is
        // both sound (no valid feeder lands earlier) and tight (no
        // point fetching further back than any spine could reach).
        Instant earliestPossibleLanding =
                earliestSpineDeparture.minus(maxLayover.multipliedBy(effectiveMaxStops));
        List<Flight> pool = flightRepository.findAllLandingInWindow(
                earliestPossibleLanding, windowEnd);

        // Hub index: destination airport -> flights landing there. All
        // subsequent lookups during recursion are Map.get() — no DB.
        Map<String, List<Flight>> landingsAt = new HashMap<>();
        for (Flight f : pool) {
            landingsAt.computeIfAbsent(f.getDestination(), k -> new ArrayList<>()).add(f);
        }

        log.debug("Batched search: inbound={} pool={} hubs={} earliestPossibleLanding={}",
                inbound.size(), pool.size(), landingsAt.size(), earliestPossibleLanding);

        for (Flight spine : inbound) {
            if (spine.getSource().equals(source)) continue; // already added as direct
            expandBackward(List.of(spine), effectiveMaxStops, source,
                    landingsAt, minLayover, maxLayover, completedPaths);
        }

        return completedPaths;
    }

    /**
     * In-memory equivalent of the recursive strategy's backward
     * expansion. Same cycle guard, same layover window, same
     * userSource-closure — the only difference is that the candidate
     * fetch is a {@code Map.get} instead of a JPA query.
     */
    private void expandBackward(List<Flight> path,
                                int remainingStops,
                                String userSource,
                                Map<String, List<Flight>> landingsAt,
                                Duration minLayover,
                                Duration maxLayover,
                                List<List<Flight>> out) {
        if (remainingStops <= 0) return;

        Flight head = path.get(0);
        Instant earliestLanding = head.getStartTime().minus(maxLayover);
        Instant latestLanding = head.getStartTime().minus(minLayover);
        Set<String> visitedAirports = FlightSearchStrategy.airportsIn(path);

        List<Flight> candidates = landingsAt.getOrDefault(head.getSource(), List.of());
        for (Flight cand : candidates) {
            // Layover-window filter (per-path, so we can't push it
            // into the pool query — the pool spans the loosest
            // possible window across all paths).
            if (cand.getEndTime().isBefore(earliestLanding)) continue;
            if (cand.getEndTime().isAfter(latestLanding)) continue;
            // fullyBooked=false is already enforced by the pool query.
            if (visitedAirports.contains(cand.getSource())) continue;

            List<Flight> newPath = FlightSearchStrategy.prepend(cand, path);
            if (cand.getSource().equals(userSource)) {
                // Path closes here — don't extend past userSource.
                out.add(newPath);
            } else if (remainingStops > 1) {
                expandBackward(newPath, remainingStops - 1, userSource,
                        landingsAt, minLayover, maxLayover, out);
            }
            // Else: at deepest allowed hop and this candidate isn't
            // userSource-rooted → dead end, drop silently.
        }
    }
}
