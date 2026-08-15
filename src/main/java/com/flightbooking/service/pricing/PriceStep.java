package com.flightbooking.service.pricing;

import java.math.BigDecimal;

/**
 * Output of one strategy in the chain. {@code newPrice} is the running total
 * passed to the next strategy; {@code note} is the human-readable reason
 * shown in the price breakdown ("1.5x last-minute surcharge").
 */
public record PriceStep(BigDecimal newPrice, String note) {}
