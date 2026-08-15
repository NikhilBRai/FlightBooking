package com.flightbooking.api.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A trip from user's source to destination. Contains 1 to
 * {@code app.search.max-stops-cap + 1} segments (default cap = 3 stops = up
 * to 4 segments). {@code stops = segments.size() - 1}, so a direct flight
 * has {@code stops = 0}. All price and time fields are already aggregated
 * across segments so the client can sort/rank without doing math.
 */
@Builder
public record ItineraryDto(
        List<FlightSummaryDto> segments,
        int stops,
        Instant startTime,
        Instant endTime,
        long totalDurationMinutes,
        /** Total ground time across every intermediate airport. 0 for direct itineraries. */
        long layoverMinutes,
        /** Sum of {@code estimatedPrice} across all segments. */
        BigDecimal totalPrice
) {}
