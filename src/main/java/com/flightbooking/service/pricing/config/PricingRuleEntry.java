package com.flightbooking.service.pricing.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One rule in the pricing chain. Every rule is an SpEL expression owned by
 * the policy team — no Java code involved in the math itself.
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code name} — display label; falls back to {@code type} if missing.</li>
 *   <li>{@code type} — discriminator; currently only {@code "expression"} is supported.
 *       Kept as a field so the loader can fail loud on typos and so future
 *       rule kinds slot in without a schema break.</li>
 *   <li>{@code order} — chain position; strategies fold from lowest to highest.</li>
 *   <li>{@code formula} — SpEL expression returning a {@code Number}; this is
 *       the entire pricing math. See {@code ExpressionStrategy.Root} for the
 *       variable vocabulary.</li>
 *   <li>{@code note} — SpEL expression returning a {@code String}; the label
 *       shown in the price breakdown. Must be quoted for literals
 *       ({@code "'base fare'"}). Supports ternaries so a single rule can
 *       produce per-branch notes ("2.0x very high demand" vs "1.0x low demand").
 *       If missing, the raw formula text is used as the note.</li>
 * </ul>
 *
 * <p>{@link JsonIgnoreProperties#ignoreUnknown} keeps the schema forward-
 * compatible when the config format grows.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PricingRuleEntry(
        String name,
        String type,
        int order,
        String formula,
        String note
) {
    /**
     * Human-readable label used in log lines, price breakdowns, and error
     * messages. Falls back to {@link #type} when {@code name} is missing
     * so half-filled entries still produce useful diagnostics.
     */
    public String displayName() {
        return name == null || name.isBlank() ? type : name;
    }
}
