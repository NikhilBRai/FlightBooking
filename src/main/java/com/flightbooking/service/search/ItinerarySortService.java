package com.flightbooking.service.search;

import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.domain.enums.SortBy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Registry + dispatcher for {@link ItinerarySorter} beans. Spring injects
 * every discovered sorter through the constructor; this service indexes them
 * by {@link SortBy} and hands each search request to the right one.
 *
 * <p><b>Boot-time guarantees:</b></p>
 * <ul>
 *   <li><b>No duplicates</b> — two sorters claiming the same {@link SortBy}
 *       crashes at startup naming both bean classes.</li>
 *   <li><b>No gaps</b> — every value of the {@link SortBy} enum must have
 *       exactly one registered sorter. Adding {@code SortBy.SHORTEST_LAYOVER}
 *       without wiring its impl crashes the app at boot rather than at the
 *       first request that asks for it.</li>
 * </ul>
 *
 * <p>Sort default is {@link #DEFAULT_SORT} — applied when the caller passes
 * {@code null} (query parameter omitted). Kept centrally so the API layer
 * doesn't have to remember the fallback.</p>
 */
@Slf4j
@Service
public class ItinerarySortService {

    /** Sort applied when the caller passes {@code null}. */
    public static final SortBy DEFAULT_SORT = SortBy.CHEAPEST;

    private final Map<SortBy, ItinerarySorter> sortersByType;

    public ItinerarySortService(List<ItinerarySorter> sorters) {
        this.sortersByType = indexByType(sorters);
        requireEnumCoverage(this.sortersByType.keySet());
        log.info("ItinerarySortService active: {} sorters -> {}",
                sortersByType.size(),
                new TreeSet<>(sortersByType.keySet()));
    }

    /**
     * Sort {@code itineraries} by {@code sortBy}, defaulting to
     * {@link #DEFAULT_SORT} when {@code null}. Input list is not mutated.
     */
    public List<ItineraryDto> sort(SortBy sortBy, List<ItineraryDto> itineraries) {
        SortBy effective = (sortBy == null) ? DEFAULT_SORT : sortBy;
        ItinerarySorter sorter = sortersByType.get(effective);
        if (sorter == null) {
            // Unreachable given the boot-time coverage check, but defensive
            // in case somebody bypasses the check via a reflective add.
            throw new IllegalStateException(
                    "No ItinerarySorter registered for " + effective
                    + " — registered: " + sortersByType.keySet());
        }
        return sorter.sort(itineraries);
    }

    private static Map<SortBy, ItinerarySorter> indexByType(List<ItinerarySorter> sorters) {
        Map<SortBy, ItinerarySorter> map = new EnumMap<>(SortBy.class);
        for (ItinerarySorter sorter : sorters) {
            ItinerarySorter previous = map.put(sorter.type(), sorter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two ItinerarySorter beans claim " + sorter.type() + ": "
                        + previous.getClass().getName() + " and "
                        + sorter.getClass().getName() + " — types must be unique.");
            }
        }
        return map;
    }

    private static void requireEnumCoverage(Set<SortBy> registered) {
        Set<SortBy> missing = EnumSet.allOf(SortBy.class);
        missing.removeAll(registered);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "SortBy values without an ItinerarySorter implementation: "
                    + missing + ". Add a @Component implementing ItinerarySorter "
                    + "that returns each missing value from type().");
        }
    }
}
