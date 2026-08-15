package com.flightbooking.service.pricing;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.service.pricing.config.PricingRuleEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Exercises the SpEL sandbox end-to-end: compilation failures at
 * build-time, evaluation semantics per variable in the Root, and the
 * sandbox refusing to run type / constructor / static-method exploits.
 *
 * <p>This is the single most trust-sensitive class in the pricing
 * subsystem — a bug here means either wrong prices in production or
 * arbitrary code execution via a JSON edit. Ergo the exhaustive
 * coverage.</p>
 */
class ExpressionStrategyTest {

    private static PriceStrategy build(String name, String formula, String note) {
        return ExpressionStrategy.from(new PricingRuleEntry(name, "expression", 0, formula, note));
    }

    private static Flight flight(BigDecimal cost, Instant departure) {
        return Flight.builder()
                .id(1L)
                .cost(cost)
                .startTime(departure)
                .endTime(departure.plusSeconds(3600))
                .source("BLR").destination("BOM")
                .build();
    }

    private static PricingContext ctx(Flight f, long available, int total, Instant now) {
        return PricingContext.builder()
                .flight(f)
                .availableSeats(available)
                .totalSeats(total)
                .now(now)
                .build();
    }

    // ---- Boot-time failures -------------------------------------------

    @Test
    void missingFormulaRejectedAtCompile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build("bad", null, "'n'"))
                .withMessageContaining("bad")
                .withMessageContaining("formula");
        assertThatIllegalStateException()
                .isThrownBy(() -> build("bad", "   ", "'n'"))
                .withMessageContaining("formula");
    }

    @Test
    void invalidSpelInFormulaRejectedAtCompile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build("bad", "((", "'n'"))
                .withMessageContaining("bad")
                .withMessageContaining("formula");
    }

    @Test
    void invalidSpelInNoteRejectedAtCompile() {
        assertThatIllegalStateException()
                .isThrownBy(() -> build("bad", "baseFare", "((")).withMessageContaining("note");
    }

    @Test
    void missingNoteFallsBackToRawFormulaTextEncodedAsSpelLiteral() {
        PriceStrategy s = build("base", "baseFare", null);
        Flight f = flight(new BigDecimal("500"), Instant.parse("2030-01-01T00:00:00Z"));
        PriceStep step = s.apply(ctx(f, 5, 10, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO);
        assertThat(step.note()).isEqualTo("baseFare");
    }

    // ---- Variable vocabulary ------------------------------------------

    @Test
    void baseFareReadsFromFlightCost() {
        PriceStrategy s = build("base", "baseFare", "'x'");
        Flight f = flight(new BigDecimal("1234"), Instant.parse("2030-01-01T00:00:00Z"));
        PriceStep out = s.apply(ctx(f, 5, 10, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO);
        assertThat(out.newPrice()).isEqualByComparingTo("1234");
    }

    @Test
    void currentPriceMultipliers() {
        PriceStrategy s = build("times2", "currentPrice * 2", "'x'");
        Flight f = flight(new BigDecimal("100"), Instant.parse("2030-01-01T00:00:00Z"));
        PriceStep out = s.apply(ctx(f, 5, 10, Instant.parse("2029-12-31T00:00:00Z")), new BigDecimal("500"));
        assertThat(out.newPrice()).isEqualByComparingTo("1000");
    }

    @Test
    void bookedRatioCoversAllBranchesOfShippedDemandRule() {
        // demand rule: >=0.90 → 2.0x, >=0.75 → 1.5x, >=0.50 → 1.2x, else 1.0x
        String formula = "currentPrice * (bookedRatio >= 0.90 ? 2.0 : bookedRatio >= 0.75 ? 1.5 : bookedRatio >= 0.50 ? 1.2 : 1.0)";
        PriceStrategy s = build("demand", formula, "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        Instant now = Instant.parse("2029-12-31T00:00:00Z");
        BigDecimal seed = new BigDecimal("100");

        assertThat(s.apply(ctx(f, 10, 10, now), seed).newPrice()).isEqualByComparingTo("100"); // 0% booked → 1.0x
        assertThat(s.apply(ctx(f, 5, 10, now), seed).newPrice()).isEqualByComparingTo("120");  // 50%       → 1.2x
        assertThat(s.apply(ctx(f, 2, 10, now), seed).newPrice()).isEqualByComparingTo("150");  // 80%       → 1.5x
        assertThat(s.apply(ctx(f, 1, 10, now), seed).newPrice()).isEqualByComparingTo("200");  // 90%       → 2.0x
    }

    @Test
    void hoursToDepartureUsesNowVsFlightStartTime() {
        PriceStrategy s = build("h", "hoursToDeparture", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T12:00:00Z"));
        PriceStep out = s.apply(ctx(f, 1, 1, Instant.parse("2030-01-01T00:00:00Z")), BigDecimal.ZERO);
        assertThat(out.newPrice()).isEqualByComparingTo("12");
    }

    @Test
    void sourceAndDestinationAvailableToRouteBasedRules() {
        PriceStrategy s = build("route",
                "source == 'BLR' and destination == 'BOM' ? 1000 : 500",
                "source + '->' + destination");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        PriceStep out = s.apply(ctx(f, 5, 10, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO);
        assertThat(out.newPrice()).isEqualByComparingTo("1000");
        assertThat(out.note()).isEqualTo("BLR->BOM");
    }

    @Test
    void bookedRatioIsZeroWhenTotalSeatsIsZero_noDivideByZero() {
        PriceStrategy s = build("safe", "bookedRatio + 42", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        PriceStep out = s.apply(ctx(f, 0, 0, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO);
        assertThat(out.newPrice()).isEqualByComparingTo("42");
    }

    // ---- Numeric contract ---------------------------------------------

    @Test
    void resultIsScaledToTwoDecimals() {
        PriceStrategy s = build("frac", "1.239", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        PriceStep out = s.apply(ctx(f, 1, 1, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO);
        assertThat(out.newPrice().scale()).isEqualTo(2);
        assertThat(out.newPrice()).isEqualByComparingTo("1.24"); // half-up rounding
    }

    @Test
    void nonNumericFormulaThrowsAtRuntime() {
        PriceStrategy s = build("bad", "'not-a-number'", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        assertThatIllegalStateException()
                .isThrownBy(() -> s.apply(ctx(f, 1, 1, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO))
                .withMessageContaining("must return a number");
    }

    // ---- Sandbox exploits (each must throw, none may execute) ---------

    @Test
    void sandboxBlocksTypeReferences() {
        // T() is how SpEL usually reaches static classes. SimpleEvaluationContext must refuse.
        PriceStrategy s = build("evil", "T(java.lang.Runtime).getRuntime().hashCode()", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        assertThatIllegalStateException()
                .isThrownBy(() -> s.apply(ctx(f, 1, 1, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO))
                .withMessageContaining("evil");
    }

    @Test
    void sandboxBlocksConstructorInvocation() {
        PriceStrategy s = build("evil", "new java.lang.String('x').length()", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T00:00:00Z"));
        assertThatIllegalStateException()
                .isThrownBy(() -> s.apply(ctx(f, 1, 1, Instant.parse("2029-12-31T00:00:00Z")), BigDecimal.ZERO))
                .withMessageContaining("evil");
    }

    // ---- Duration semantics -------------------------------------------

    @Test
    void hoursToDepartureIsPositiveWhenNowIsBeforeDeparture() {
        PriceStrategy s = build("h", "hoursToDeparture", "'x'");
        Flight f = flight(new BigDecimal("1"), Instant.parse("2030-01-01T12:00:00Z"));
        PriceStep out = s.apply(ctx(f, 1, 1, Instant.parse("2030-01-01T00:00:00Z").minus(Duration.ofHours(3))), BigDecimal.ZERO);
        assertThat(out.newPrice()).isEqualByComparingTo("15");
    }
}
