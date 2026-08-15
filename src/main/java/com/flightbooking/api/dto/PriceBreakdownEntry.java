package com.flightbooking.api.dto;

import java.math.BigDecimal;

/**
 * One row in the price breakdown returned by the search / details / reserve
 * responses. Useful for UI transparency ("Base ₹4500 → 1.5x same-day → ₹6750").
 */
public record PriceBreakdownEntry(
        String strategy,
        BigDecimal price,
        String note
) {}
