package com.flightbooking.service.pricing;

import com.flightbooking.domain.entity.Flight;
import lombok.Builder;

import java.time.Instant;

/**
 * Inputs available to a {@link PriceStrategy} — enough signal to price on
 * base fare, seat inventory, and departure timing. Extend cautiously —
 * anything expensive to compute belongs in the caller so search stays cheap.
 */
@Builder
public record PricingContext(
        Flight flight,
        long availableSeats,
        int totalSeats,
        Instant now
) {}
