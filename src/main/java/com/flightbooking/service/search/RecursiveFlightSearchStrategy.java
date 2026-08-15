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
import java.util.List;
import java.util.Set;

/**
 * Original bounded-backward-expansion search. Runs one DB query per
 * recursion node — a single {@code findInboundInWindow} for the spine,
 * then {@code findLandingAtHubInWindow} at each intermediate hop and
 * {@code findFeederLegs} at the deepest hop.
 *
 * <p>Trade-off vs {@link BatchedFlightSearchStrategy}: query count grows
 * with the size of the recursion <em>tree</em> (roughly
 * {@code O(inbound × hubs × candidates_per_hub)}), which is fine when
 * the depth cap is small and hubs are few, but degrades quickly on
 * denser networks. Kept as a switchable option ({@code
 * app.search.strategy=recursive}) primarily to make it obvious in
 * benchmarks/logs how much the batched impl actually buys us.</p>
 *
 * <p>Behaviour is preserved verbatim from the pre-refactor
 * {@code FlightService.expandBackward} — the same cycle guard, the
 * same layover window, the same last-hop optimisation.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.strategy", havingValue = "recursive")
public class RecursiveFlightSearchStrategy implements FlightSearchStrategy {

    private final FlightRepository flightRepository;

    @Value("${app.search.min-layover-minutes:60}")
    private long minLayoverMinutes;

    @Value("${app.search.max-layover-hours:12}")
    private long maxLayoverHours;

    @Override
    public String name() {
        return "recursive";
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
        for (Flight spine : inbound) {
            if (spine.getSource().equals(source)) {
                completedPaths.add(List.of(spine));
            } else if (effectiveMaxStops >= 1) {
                expandBackward(List.of(spine), effectiveMaxStops, source,
                        minLayover, maxLayover, completedPaths);
            }
        }
        return completedPaths;
    }

    /**
     * Backward-recursively prepend feeder flights onto {@code path} up
     * to {@code remainingStops} more hops. Emits a completed path into
     * {@code out} whenever it reaches a feeder whose source is
     * {@code userSource}.
     *
     * <p>Cycle guard: never prepend a feeder whose source is already
     * an airport visited by the current path — otherwise a 3-stop
     * search would happily emit BLR → HYD → BLR → HYD → BOM.</p>
     */
    private void expandBackward(List<Flight> path,
                                int remainingStops,
                                String userSource,
                                Duration minLayover,
                                Duration maxLayover,
                                List<List<Flight>> out) {
        if (remainingStops <= 0) return;

        Flight head = path.get(0);
        Instant earliestLanding = head.getStartTime().minus(maxLayover);
        Instant latestLanding = head.getStartTime().minus(minLayover);

        if (remainingStops == 1) {
            // Deepest hop — only a userSource-rooted feeder can close
            // the trip. No cycle check needed: findFeederLegs already
            // pins source=userSource, and userSource is never on the
            // path (we never recurse past it).
            List<Flight> feeders = flightRepository.findFeederLegs(
                    userSource, head.getSource(), earliestLanding, latestLanding);
            for (Flight feeder : feeders) {
                out.add(FlightSearchStrategy.prepend(feeder, path));
            }
            return;
        }

        // Room to go deeper — consider any landing at the hub. Here
        // the cycle guard matters: findLandingAtHubInWindow returns
        // candidates from any source, so without it we'd happily emit
        // BLR → HYD → BLR → HYD → BOM.
        Set<String> visitedAirports = FlightSearchStrategy.airportsIn(path);
        List<Flight> candidates = flightRepository.findLandingAtHubInWindow(
                head.getSource(), earliestLanding, latestLanding);
        for (Flight cand : candidates) {
            if (visitedAirports.contains(cand.getSource())) continue;
            List<Flight> newPath = FlightSearchStrategy.prepend(cand, path);
            if (cand.getSource().equals(userSource)) {
                // Path closes here — don't extend past userSource.
                out.add(newPath);
            } else {
                expandBackward(newPath, remainingStops - 1, userSource,
                        minLayover, maxLayover, out);
            }
        }
    }
}
