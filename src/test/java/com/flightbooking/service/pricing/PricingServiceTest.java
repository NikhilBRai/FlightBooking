package com.flightbooking.service.pricing;

import com.flightbooking.domain.entity.Flight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PricingService is a pure fold — we don't need SpEL, just the
 * strategy contract. Fake strategies let us assert:
 *   - order is preserved (chain folds left-to-right by loader order)
 *   - each step sees the previous step's output as currentPrice
 *   - breakdown length == strategies.size() and each entry carries
 *     the running-total price at that step
 *   - finalPrice == last step's newPrice
 *   - zero strategies is impossible (loader guards it) so no test for it.
 */
class PricingServiceTest {

    private static PriceStrategy step(String name, BigDecimal newPrice, String note) {
        return new PriceStrategy() {
            @Override public String name() { return name; }
            @Override public PriceStep apply(PricingContext ctx, BigDecimal currentPrice) {
                return new PriceStep(newPrice, note);
            }
        };
    }

    /** Doubles the current price and records what it received, so we can
     *  assert chain composition (the "did step N see step N-1's output?" check). */
    private static class RecordingDoubler implements PriceStrategy {
        BigDecimal sawCurrentPrice;
        @Override public String name() { return "doubler"; }
        @Override public PriceStep apply(PricingContext ctx, BigDecimal currentPrice) {
            this.sawCurrentPrice = currentPrice;
            return new PriceStep(currentPrice.multiply(BigDecimal.valueOf(2)), "2x");
        }
    }

    /**
     * The real loader would parse pricing-rules.json — we subclass it so
     * we can inject an arbitrary chain without touching the file, and
     * without asking Mockito to synthesise a subclass of a boot-time
     * component (which trips over JDK/ByteBuddy inline-mock quirks on
     * newer JVMs).
     */
    private static PricingRulesLoader loaderReturning(List<PriceStrategy> chain) {
        return new PricingRulesLoader(
                "classpath:pricing-rules.json",
                new DefaultResourceLoader(),
                new ObjectMapper()) {
            @Override public List<PriceStrategy> getStrategies() { return chain; }
        };
    }

    private static PricingContext ctx() {
        Flight f = Flight.builder()
                .id(1L)
                .cost(new BigDecimal("1000"))
                .startTime(Instant.parse("2030-01-01T00:00:00Z"))
                .endTime(Instant.parse("2030-01-01T02:00:00Z"))
                .source("BLR").destination("BOM")
                .build();
        return PricingContext.builder()
                .flight(f)
                .availableSeats(10)
                .totalSeats(10)
                .now(Instant.parse("2029-12-31T00:00:00Z"))
                .build();
    }

    @Test
    void foldsStrategiesLeftToRight_finalPriceIsLastStep() {
        PricingService svc = new PricingService(loaderReturning(List.of(
                step("base", new BigDecimal("100"), "seed"),
                step("plus50", new BigDecimal("150"), "+50"),
                step("half",   new BigDecimal("75"),  "0.5x")
        )));

        PriceQuote quote = svc.quote(ctx());

        assertThat(quote.finalPrice()).isEqualByComparingTo("75");
        assertThat(quote.breakdown()).hasSize(3);
        assertThat(quote.breakdown().get(0).strategy()).isEqualTo("base");
        assertThat(quote.breakdown().get(0).price()).isEqualByComparingTo("100");
        assertThat(quote.breakdown().get(0).note()).isEqualTo("seed");
        assertThat(quote.breakdown().get(1).price()).isEqualByComparingTo("150");
        assertThat(quote.breakdown().get(2).price()).isEqualByComparingTo("75");
    }

    @Test
    void eachStepSeesPreviousStepsOutputAsCurrentPrice() {
        RecordingDoubler doubler = new RecordingDoubler();
        PricingService svc = new PricingService(loaderReturning(List.of(
                step("seed", new BigDecimal("50"), "start"),
                doubler
        )));

        PriceQuote quote = svc.quote(ctx());

        assertThat(doubler.sawCurrentPrice).isEqualByComparingTo("50");
        assertThat(quote.finalPrice()).isEqualByComparingTo("100");
    }

    @Test
    void firstStrategyReceivesZeroCurrentPrice() {
        RecordingDoubler first = new RecordingDoubler();
        PricingService svc = new PricingService(loaderReturning(List.of(first)));

        svc.quote(ctx());

        assertThat(first.sawCurrentPrice).isEqualByComparingTo("0");
    }

    @Test
    void convenienceOverloadBuildsContextFromFlightAndCounts() {
        PricingService svc = new PricingService(loaderReturning(List.of(
                step("base", new BigDecimal("999"), "flat")
        )));
        Flight f = Flight.builder()
                .id(1L)
                .cost(new BigDecimal("1000"))
                .startTime(Instant.parse("2030-01-01T00:00:00Z"))
                .endTime(Instant.parse("2030-01-01T02:00:00Z"))
                .source("BLR").destination("BOM")
                .build();

        PriceQuote quote = svc.quote(f, 3, 10);

        assertThat(quote.finalPrice()).isEqualByComparingTo("999");
        assertThat(quote.breakdown()).hasSize(1);
    }
}
