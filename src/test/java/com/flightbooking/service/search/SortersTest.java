package com.flightbooking.service.search;

import com.flightbooking.api.dto.ItineraryDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Small but complete coverage of the two sorters. We deliberately test
 * <b>tie-breakers</b> — the secondary comparator is easy to forget
 * when refactoring, and its absence produces "sometimes-flaky-order"
 * bugs that fuzz tests would miss.
 */
class SortersTest {

    private static ItineraryDto itinerary(String label, long minutes, BigDecimal price) {
        // Only the two comparator fields are ever read by the sorter;
        // everything else is filler to satisfy the record.
        return ItineraryDto.builder()
                .segments(List.of())
                .stops(0)
                .startTime(Instant.EPOCH)
                .endTime(Instant.EPOCH.plusSeconds(minutes * 60))
                .totalDurationMinutes(minutes)
                .layoverMinutes(0)
                .totalPrice(price)
                .build();
    }

    @Nested
    @DisplayName("CheapestSorter")
    class Cheapest {

        private final CheapestSorter sorter = new CheapestSorter();

        @Test
        void ordersByPriceAscending() {
            ItineraryDto a = itinerary("a", 100, new BigDecimal("500"));
            ItineraryDto b = itinerary("b", 100, new BigDecimal("100"));
            ItineraryDto c = itinerary("c", 100, new BigDecimal("300"));

            List<ItineraryDto> sorted = sorter.sort(List.of(a, b, c));

            assertThat(sorted).extracting(ItineraryDto::totalPrice)
                    .containsExactly(
                            new BigDecimal("100"),
                            new BigDecimal("300"),
                            new BigDecimal("500"));
        }

        @Test
        void tieBreaksOnShorterDuration() {
            ItineraryDto slow = itinerary("slow", 240, new BigDecimal("100"));
            ItineraryDto fast = itinerary("fast", 120, new BigDecimal("100"));

            List<ItineraryDto> sorted = sorter.sort(List.of(slow, fast));

            assertThat(sorted).extracting(ItineraryDto::totalDurationMinutes)
                    .containsExactly(120L, 240L);
        }

        @Test
        void returnsNewListWithoutMutatingInput() {
            ItineraryDto a = itinerary("a", 100, new BigDecimal("500"));
            ItineraryDto b = itinerary("b", 100, new BigDecimal("100"));
            List<ItineraryDto> input = List.of(a, b);

            List<ItineraryDto> sorted = sorter.sort(input);

            assertThat(input).containsExactly(a, b);
            assertThat(sorted).isNotSameAs(input);
        }

        @Test
        void handlesEmptyList() {
            assertThat(sorter.sort(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("FastestSorter")
    class Fastest {

        private final FastestSorter sorter = new FastestSorter();

        @Test
        void ordersByDurationAscending() {
            ItineraryDto a = itinerary("a", 300, new BigDecimal("100"));
            ItineraryDto b = itinerary("b", 100, new BigDecimal("100"));
            ItineraryDto c = itinerary("c", 200, new BigDecimal("100"));

            List<ItineraryDto> sorted = sorter.sort(List.of(a, b, c));

            assertThat(sorted).extracting(ItineraryDto::totalDurationMinutes)
                    .containsExactly(100L, 200L, 300L);
        }

        @Test
        void tieBreaksOnLowerPrice() {
            ItineraryDto expensive = itinerary("exp", 120, new BigDecimal("999"));
            ItineraryDto cheap = itinerary("cheap", 120, new BigDecimal("100"));

            List<ItineraryDto> sorted = sorter.sort(List.of(expensive, cheap));

            assertThat(sorted).extracting(ItineraryDto::totalPrice)
                    .containsExactly(new BigDecimal("100"), new BigDecimal("999"));
        }
    }
}
