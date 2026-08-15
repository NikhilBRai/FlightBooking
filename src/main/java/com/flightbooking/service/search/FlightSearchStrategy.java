package com.flightbooking.service.search;

import com.flightbooking.domain.entity.Flight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pluggable "given (source, destination, date, maxStops), enumerate every
 * completed itinerary path" algorithm. Implementations differ purely in
 * how they exchange DB round-trips for CPU / memory — the returned paths
 * are indistinguishable to downstream pricing and sorting.
 *
 * <p><b>Return contract.</b> Each element is one itinerary, materialised
 * as a chronologically-ordered {@code List<Flight>} whose first flight
 * departs from {@code source} and whose last flight lands at
 * {@code destination}. A direct itinerary is a one-element list. The
 * outer list is unordered (sorting is a separate concern handled by
 * {@link ItinerarySortService}).</p>
 *
 * <p><b>Non-goals.</b> This interface intentionally exposes raw
 * {@link Flight} paths, not priced DTOs, so pricing (which depends on
 * per-flight seat availability and needs to be batched across the union
 * of all paths) stays in {@code FlightService}. That keeps every
 * strategy honest — algorithm changes never accidentally leak into
 * pricing behaviour.</p>
 *
 * <p><b>Contract every strategy must honour.</b></p>
 * <ul>
 *   <li>{@code effectiveMaxStops} is the maximum <em>connections</em>
 *       (i.e. layovers) allowed — a two-flight itinerary has 1 stop.
 *       The caller pre-clamps this to {@code app.search.max-stops-cap}
 *       so the strategy can trust the value it receives.</li>
 *   <li>Feeder legs' arrival-to-next-departure gap must fall within the
 *       {@code [min-layover-minutes, max-layover-hours]} window.</li>
 *   <li>{@code fullyBooked=true} segments are excluded — search must
 *       never surface a segment the user couldn't actually book.</li>
 *   <li>An airport visited earlier on the path cannot be revisited
 *       (no BLR → HYD → BLR → HYD → BOM loops).</li>
 * </ul>
 */
public interface FlightSearchStrategy {

    /**
     * Enumerate every itinerary of at most {@code effectiveMaxStops}
     * stops that departs from {@code source} on {@code date} and lands
     * at {@code destination}. The date is interpreted as the departure
     * date of the <em>last</em> leg (the "spine"), in UTC.
     */
    List<List<Flight>> findPaths(String source,
                                 String destination,
                                 LocalDate date,
                                 int effectiveMaxStops);

    /** Short identifier for logs (e.g. {@code "recursive"}, {@code "batched"}). */
    String name();

    // ---- Shared helpers ------------------------------------------------
    //
    // Both concrete strategies drive a backward expansion and need the
    // same cycle-detection + immutable-prepend primitives. They live on
    // the interface as static utilities so neither impl has to import
    // the other, and there's no abstract base class to jump through.

    /** Airports (source + destination of every leg) visited by {@code path}. */
    static Set<String> airportsIn(List<Flight> path) {
        Set<String> airports = new HashSet<>();
        for (Flight f : path) {
            airports.add(f.getSource());
            airports.add(f.getDestination());
        }
        return airports;
    }

    /**
     * Return a new list with {@code feeder} prepended in front of {@code path}.
     * {@code path} is not mutated — safe to share a partial path across
     * multiple recursive branches.
     */
    static List<Flight> prepend(Flight feeder, List<Flight> path) {
        List<Flight> extended = new ArrayList<>(path.size() + 1);
        extended.add(feeder);
        extended.addAll(path);
        return extended;
    }
}
