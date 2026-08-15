package com.flightbooking.api.dto;

import com.flightbooking.domain.enums.BookingStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Top-level read model for a persisted itinerary. Returned by every
 * mutating itinerary endpoint ({@code reserve}, {@code confirm},
 * {@code cancel}) and by {@code GET /itinerary/{id}}.
 *
 * <p>A direct-flight reservation surfaces as a size-1
 * {@link #legs} list; a two-hop trip as a size-2 list, and so on.
 * Aggregated {@link #totalFinalPrice} is the sum of each leg's
 * {@link BookingDto#finalPrice()}, cached at reserve time.</p>
 *
 * <p>{@link #expiresAt} is meaningful while the itinerary is in
 * {@link BookingStatus#RESERVED} — the Redis seat-lock TTL fires at
 * that instant for every leg. It stays populated after confirm /
 * cancel so callers can audit when the lock originally would have
 * expired.</p>
 *
 * <p>{@link #message} is populated only by the reserve response,
 * where it carries the client-facing "reserved, confirm within N
 * minutes" hint. Null everywhere else.</p>
 */
@Builder
public record BookingItineraryDto(
        Long itineraryId,
        Long userId,
        BookingStatus status,
        BigDecimal totalFinalPrice,
        Instant reservedAt,
        Instant expiresAt,
        Instant confirmedAt,
        Instant cancelledAt,
        List<BookingDto> legs,
        String message
) {}
