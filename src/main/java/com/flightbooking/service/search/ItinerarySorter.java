package com.flightbooking.service.search;

import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.domain.enums.SortBy;

import java.util.List;

/**
 * Plugin contract for one itinerary sort order. Each implementation is a
 * Spring {@code @Component} that self-declares which {@link SortBy} value
 * it handles; {@link ItinerarySortService} discovers them via constructor
 * injection and dispatches per request.
 *
 * <p>Encoding each sort as its own class means:</p>
 * <ul>
 *   <li>Adding {@code SortBy.SHORTEST_LAYOVER} is a new file, zero edits
 *       to {@code FlightService}.</li>
 *   <li>Missing an enum value crashes the app at boot instead of at the
 *       first request that tries to use it (see the boot check in
 *       {@link ItinerarySortService}).</li>
 *   <li>Two sorters claiming the same {@link SortBy} crashes at boot with
 *       both bean names, not silently overwrites.</li>
 * </ul>
 *
 * <p><b>Contract:</b> {@link #sort} must return a new list; do not mutate
 * the input. Implementations should be stateless so the shared bean is
 * safe under concurrent search requests.</p>
 */
public interface ItinerarySorter {

    /** The sort mode this implementation handles. Unique across all beans. */
    SortBy type();

    /** Return a new list ordered by this sort's criterion. Input is not mutated. */
    List<ItineraryDto> sort(List<ItineraryDto> itineraries);
}
