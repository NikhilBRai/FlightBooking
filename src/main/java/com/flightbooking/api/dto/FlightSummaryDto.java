package com.flightbooking.api.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Per-<em>segment</em> projection returned inside an {@link ItineraryDto}.
 * Every persisted flight is a direct point-to-point segment now — multi-stop
 * itineraries are built at search time by chaining segments together.
 *
 * <p>{@code baseFare} is the flight's stored fare; {@code estimatedPrice} is
 * what the pricing chain quotes for this segment right now.
 * {@code priceBreakdown} explains how each strategy moved the price.
 * The estimate is a <em>quote</em>, not a guarantee — the authoritative price
 * is locked in when the user calls {@code POST /booking/reserve}.</p>
 */
@Builder
public record FlightSummaryDto(
        Long flightId,
        String source,
        String destination,
        Instant startTime,
        Instant endTime,
        long durationMinutes,
        BigDecimal baseFare,
        BigDecimal estimatedPrice,
        List<PriceBreakdownEntry> priceBreakdown,
        String aircraft,
        boolean fullyBooked
) {}
