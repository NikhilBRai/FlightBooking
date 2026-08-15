package com.flightbooking.service.pricing;

import com.flightbooking.api.dto.PriceBreakdownEntry;
import com.flightbooking.domain.entity.Flight;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds every configured {@link PriceStrategy} over the fare. Callers pass a
 * {@link PricingContext} carrying the flight and the seat-availability signal —
 * the strategy chain does the rest.
 *
 * <p>The chain is loaded from {@code pricing-rules.json} by
 * {@link PricingRulesLoader}. Adding, reordering, or reweighting a rule is a
 * JSON edit — no rebuild. Adding a brand-new strategy <em>type</em> is a
 * one-file class plus a new case in the loader's dispatch.</p>
 */
@Slf4j
@Service
public class PricingService {

    private final List<PriceStrategy> strategies;

    public PricingService(PricingRulesLoader rulesLoader) {
        this.strategies = rulesLoader.getStrategies();
        log.info("PricingService active with {} strategies: {}",
                strategies.size(),
                strategies.stream().map(PriceStrategy::name).toList());
    }

    public PriceQuote quote(PricingContext ctx) {
        BigDecimal running = BigDecimal.ZERO;
        List<PriceBreakdownEntry> breakdown = new ArrayList<>(strategies.size());
        for (PriceStrategy strategy : strategies) {
            PriceStep step = strategy.apply(ctx, running);
            running = step.newPrice();
            breakdown.add(new PriceBreakdownEntry(strategy.name(), running, step.note()));
        }
        return new PriceQuote(running, breakdown);
    }

    /** Convenience for callers that already have the flight + availability count. */
    public PriceQuote quote(Flight flight, long availableSeats, int totalSeats) {
        return quote(PricingContext.builder()
                .flight(flight)
                .availableSeats(availableSeats)
                .totalSeats(totalSeats)
                .now(Instant.now())
                .build());
    }
}
