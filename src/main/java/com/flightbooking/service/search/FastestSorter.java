package com.flightbooking.service.search;

import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.domain.enums.SortBy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Fastest itineraries first — ascending by
 * {@link ItineraryDto#totalDurationMinutes} (departure to arrival, layovers
 * included). Tie-breaker on total price so two same-duration itineraries
 * surface the cheaper one first.
 */
@Component
public class FastestSorter implements ItinerarySorter {

    private static final Comparator<ItineraryDto> ORDER =
            Comparator.comparingLong(ItineraryDto::totalDurationMinutes)
                    .thenComparing(ItineraryDto::totalPrice);

    @Override
    public SortBy type() {
        return SortBy.FASTEST;
    }

    @Override
    public List<ItineraryDto> sort(List<ItineraryDto> itineraries) {
        return itineraries.stream().sorted(ORDER).toList();
    }
}
