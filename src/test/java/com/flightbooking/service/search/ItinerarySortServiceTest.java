package com.flightbooking.service.search;

import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.domain.enums.SortBy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Locks down the dispatcher's two boot-time invariants (no duplicates,
 * no gaps) and its runtime fallback behaviour (null → default).
 */
class ItinerarySortServiceTest {

    private static ItineraryDto itin(long duration, BigDecimal price) {
        return ItineraryDto.builder()
                .segments(List.of())
                .stops(0)
                .startTime(Instant.EPOCH)
                .endTime(Instant.EPOCH.plusSeconds(duration * 60))
                .totalDurationMinutes(duration)
                .layoverMinutes(0)
                .totalPrice(price)
                .build();
    }

    @Test
    void routesToCorrectSorterAndReturnsSorted() {
        ItinerarySortService svc = new ItinerarySortService(
                List.of(new CheapestSorter(), new FastestSorter()));

        ItineraryDto slow = itin(300, new BigDecimal("100"));
        ItineraryDto fast = itin(100, new BigDecimal("999"));

        assertThat(svc.sort(SortBy.FASTEST, List.of(slow, fast)).get(0))
                .isEqualTo(fast);
        assertThat(svc.sort(SortBy.CHEAPEST, List.of(slow, fast)).get(0))
                .isEqualTo(slow);
    }

    @Test
    void nullSortByFallsBackToCheapestDefault() {
        ItinerarySortService svc = new ItinerarySortService(
                List.of(new CheapestSorter(), new FastestSorter()));

        ItineraryDto expensive = itin(100, new BigDecimal("999"));
        ItineraryDto cheap = itin(500, new BigDecimal("50"));

        assertThat(svc.sort(null, List.of(expensive, cheap)).get(0)).isEqualTo(cheap);
    }

    @Test
    void bootFailsIfTwoSortersClaimSameSortBy() {
        // Both sorters claim CHEAPEST.
        ItinerarySorter dup1 = new ItinerarySorter() {
            @Override public SortBy type() { return SortBy.CHEAPEST; }
            @Override public List<ItineraryDto> sort(List<ItineraryDto> in) { return in; }
        };
        ItinerarySorter dup2 = new ItinerarySorter() {
            @Override public SortBy type() { return SortBy.CHEAPEST; }
            @Override public List<ItineraryDto> sort(List<ItineraryDto> in) { return in; }
        };

        assertThatIllegalStateException()
                .isThrownBy(() -> new ItinerarySortService(List.of(dup1, dup2)))
                .withMessageContaining("CHEAPEST");
    }

    @Test
    void bootFailsIfAnySortByValueHasNoSorter() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ItinerarySortService(List.of(new CheapestSorter())))
                .withMessageContaining("FASTEST");
    }
}
