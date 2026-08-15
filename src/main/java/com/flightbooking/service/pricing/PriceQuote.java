package com.flightbooking.service.pricing;

import com.flightbooking.api.dto.PriceBreakdownEntry;

import java.math.BigDecimal;
import java.util.List;

/** Result of a pricing pass. */
public record PriceQuote(
        BigDecimal finalPrice,
        List<PriceBreakdownEntry> breakdown
) {}
