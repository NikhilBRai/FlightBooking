package com.flightbooking.service.search;

import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.domain.enums.SortBy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Cheapest itineraries first — ascending by {@link ItineraryDto#totalPrice}.
 * Tie-breaker on total duration so two same-price itineraries surface the
 * shorter one first (nicer UX than arbitrary order).
 */
@Component
public class CheapestSorter implements ItinerarySorter {

    private static final Comparator<ItineraryDto> ORDER =
            Comparator.comparing(ItineraryDto::totalPrice)
                    .thenComparingLong(ItineraryDto::totalDurationMinutes);

    @Override
    public SortBy type() {
        return SortBy.CHEAPEST;
    }

    @Override
    public List<ItineraryDto> sort(List<ItineraryDto> itineraries) {
        return itineraries.stream().sorted(ORDER).toList();
    }
}
