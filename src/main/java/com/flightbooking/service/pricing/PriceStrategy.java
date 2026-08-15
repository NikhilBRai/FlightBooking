package com.flightbooking.service.pricing;

import java.math.BigDecimal;

/**
 * One link in the dynamic-pricing chain. Instances are constructed by
 * {@link PricingRulesLoader} from JSON entries and folded (in {@code order})
 * by {@link PricingService}.
 *
 * <p>Contract: each strategy receives the running price from the previous
 * step and returns the adjusted price plus a note. The first strategy in
 * the chain typically <em>sets</em> the initial price by ignoring
 * {@code currentPrice} (e.g. a JSON rule with formula {@code "baseFare"});
 * subsequent strategies multiply or add on top.</p>
 *
 * <p>Currently the only implementation is {@link ExpressionStrategy} —
 * every pricing rule ships as a SpEL expression owned by the policy team.</p>
 */
public interface PriceStrategy {

    /** Display name shown in the price breakdown. */
    String name();

    PriceStep apply(PricingContext ctx, BigDecimal currentPrice);
}
