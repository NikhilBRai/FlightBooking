package com.flightbooking.service.pricing.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Root of {@code pricing-rules.json}. Wraps a flat, ordered list of strategies.
 * Everything the policy team can tune lives underneath — no code changes needed
 * to add, remove, reorder, or reweight a pricing rule.
 *
 * <p>{@code ignoreUnknown = true} so the JSON can carry {@code _comment}
 * fields or future schema extensions without breaking older deployments.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingRulesConfig(List<PricingRuleEntry> strategies) {}
