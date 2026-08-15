package com.flightbooking.api.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read model for one leg of an itinerary. Status and lifecycle
 * timestamps live on the parent {@link BookingItineraryDto} because
 * every leg of an itinerary shares them — a mid-itinerary
 * "confirmed for leg 1 but reserved for leg 2" state is impossible
 * by design.
 *
 * <p>{@link #priceBreakdown} is populated only on the reserve
 * response (where the pricing chain just ran); it is {@code null}
 * for confirm / cancel / get replies where the breakdown wasn't
 * re-computed. Clients that need the breakdown after reserve should
 * cache it from that response.</p>
 */
@Builder
public record BookingDto(
        Long bookingId,
        int legOrder,
        Long flightId,
        String source,
        String destination,
        Long seatId,
        String seatNumber,
        BigDecimal finalPrice,
        List<PriceBreakdownEntry> priceBreakdown
) {}
