package com.flightbooking.service.pricing;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.service.pricing.config.PricingRuleEntry;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The single pricing strategy. All math lives in the policy team's JSON —
 * this class is a sandboxed SpEL runtime that compiles the {@code formula}
 * and {@code note} expressions at boot and evaluates them per quote.
 *
 * <p>There is intentionally no other strategy class. Anything the policy
 * team wants to price on — base fare, demand curves, time-to-departure,
 * taxes, route surcharges — is a formula. Java owns the runtime; policy
 * owns the calculation.</p>
 *
 * <p><b>Example JSON:</b></p>
 * <pre>{@code
 * {
 *   "name":    "demand-based",
 *   "type":    "expression",
 *   "order":   10,
 *   "formula": "currentPrice * (bookedRatio >= 0.9 ? 2.0 : bookedRatio >= 0.75 ? 1.5 : bookedRatio >= 0.5 ? 1.2 : 1.0)",
 *   "note":    "bookedRatio >= 0.9 ? '2.0x very high demand' : bookedRatio >= 0.75 ? '1.5x high demand' : bookedRatio >= 0.5 ? '1.2x moderate demand' : '1.0x low demand'"
 * }
 * }</pre>
 *
 * <p><b>Variables the DSL exposes</b> (see {@link Root}):</p>
 * <ul>
 *   <li>{@code currentPrice} — running price from the previous step (BigDecimal)</li>
 *   <li>{@code baseFare} — {@code flight.cost}</li>
 *   <li>{@code bookedRatio} — 0.0..1.0, fraction of seats sold</li>
 *   <li>{@code hoursToDeparture} — long, whole hours between now and takeoff</li>
 *   <li>{@code availableSeats}, {@code totalSeats}</li>
 *   <li>{@code source}, {@code destination} — airport codes for route-based rules</li>
 *   <li>{@code flight} — the {@link Flight} entity (read-only access to all getters)</li>
 *   <li>{@code now} — quote timestamp</li>
 * </ul>
 *
 * <p><b>Sandboxing.</b> We evaluate with {@link SimpleEvaluationContext} in
 * <em>read-only data binding</em> mode with instance methods enabled. This
 * blocks type references ({@code T(java.lang.Runtime)}), constructors, and
 * static method calls — a formula cannot execute arbitrary code or reflect
 * on the JVM. It CAN read properties, call public methods on the root, and
 * do arithmetic / comparisons / ternaries.</p>
 *
 * <p><b>Trust model.</b> The pricing-rules file is policy-team-owned and
 * code-reviewed like any other config artifact — the sandbox is defense in
 * depth, not a substitute for review.</p>
 *
 * <p><b>Fail-loud.</b> A malformed formula/note crashes the app at boot
 * with the offending rule name and the raw SpEL text. A runtime failure
 * (divide by zero, sandbox violation) surfaces as a 500 at quote time —
 * better than silently returning a garbage price.</p>
 */
public final class ExpressionStrategy implements PriceStrategy {

    /** Wire-format discriminator for this strategy kind. */
    public static final String TYPE = "expression";

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    /**
     * Reused across calls — {@link SimpleEvaluationContext} is thread-safe
     * once built. Only the root object varies per invocation.
     */
    private static final SimpleEvaluationContext SHARED_CONTEXT =
            SimpleEvaluationContext
                    .forReadOnlyDataBinding()
                    .withInstanceMethods()
                    .build();

    private final String name;
    private final Expression formula;
    private final Expression noteExpression;
    private final String rawFormula;
    private final String rawNote;

    private ExpressionStrategy(String name,
                               Expression formula,
                               Expression noteExpression,
                               String rawFormula,
                               String rawNote) {
        this.name = Objects.requireNonNull(name, "name");
        this.formula = Objects.requireNonNull(formula, "formula");
        this.noteExpression = Objects.requireNonNull(noteExpression, "noteExpression");
        this.rawFormula = Objects.requireNonNull(rawFormula, "rawFormula");
        this.rawNote = Objects.requireNonNull(rawNote, "rawNote");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public PriceStep apply(PricingContext ctx, BigDecimal currentPrice) {
        Root root = new Root(ctx, currentPrice);

        Object priceResult = eval(formula, root, "formula", rawFormula);
        BigDecimal newPrice = toBigDecimal(priceResult)
                .setScale(2, RoundingMode.HALF_UP);

        Object noteResult = eval(noteExpression, root, "note", rawNote);
        String noteText = noteResult == null ? "" : noteResult.toString();

        return new PriceStep(newPrice, noteText);
    }

    /** Construct a strategy instance from its JSON entry. Referenced by the loader's dispatch map. */
    public static PriceStrategy from(PricingRuleEntry entry) {
        String rawFormula = entry.formula();
        if (rawFormula == null || rawFormula.isBlank()) {
            throw new IllegalStateException(
                    "expression rule '" + entry.displayName()
                    + "' is missing required field 'formula'");
        }
        Expression compiledFormula = compile(rawFormula, "formula", entry);

        // Default the note expression to the formula text as a SpEL string literal,
        // so rules without an explicit note still show something meaningful in the
        // breakdown. Users override with a real SpEL string expression.
        String rawNote = (entry.note() == null || entry.note().isBlank())
                ? "'" + rawFormula.replace("'", "''") + "'"
                : entry.note();
        Expression compiledNote = compile(rawNote, "note", entry);

        return new ExpressionStrategy(
                entry.displayName(), compiledFormula, compiledNote, rawFormula, rawNote);
    }

    private static Expression compile(String raw, String fieldName, PricingRuleEntry entry) {
        try {
            return PARSER.parseExpression(raw);
        } catch (SpelParseException e) {
            throw new IllegalStateException(
                    "expression rule '" + entry.displayName()
                    + "' has invalid SpEL in '" + fieldName + "': " + raw, e);
        }
    }

    private Object eval(Expression expr, Root root, String fieldName, String rawSource) {
        try {
            return expr.getValue(SHARED_CONTEXT, root);
        } catch (SpelEvaluationException e) {
            throw new IllegalStateException(
                    "Expression rule '" + name + "' failed at runtime evaluating '"
                    + fieldName + "'. Source: " + rawSource, e);
        }
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            throw new IllegalStateException("expression returned null; must return a number");
        }
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        throw new IllegalStateException(
                "expression must return a number, got "
                + o.getClass().getSimpleName() + ": " + o);
    }

    /**
     * Root object exposed to SpEL. Public class with public getters so
     * {@code SimpleEvaluationContext}'s reflective property accessor can
     * reach them. The bean-property names ({@code bookedRatio},
     * {@code hoursToDeparture}, …) are what the formula authors see.
     */
    public static class Root {
        private final PricingContext ctx;
        private final BigDecimal currentPrice;

        public Root(PricingContext ctx, BigDecimal currentPrice) {
            this.ctx = ctx;
            this.currentPrice = currentPrice;
        }

        public BigDecimal getCurrentPrice() {
            return currentPrice;
        }

        public BigDecimal getBaseFare() {
            return ctx.flight().getCost();
        }

        public long getAvailableSeats() {
            return ctx.availableSeats();
        }

        public int getTotalSeats() {
            return ctx.totalSeats();
        }

        public double getBookedRatio() {
            int total = ctx.totalSeats();
            if (total <= 0) return 0.0;
            return 1.0 - ((double) ctx.availableSeats() / (double) total);
        }

        public long getHoursToDeparture() {
            return Duration.between(ctx.now(), ctx.flight().getStartTime()).toHours();
        }

        public String getSource() {
            return ctx.flight().getSource();
        }

        public String getDestination() {
            return ctx.flight().getDestination();
        }

        public Flight getFlight() {
            return ctx.flight();
        }

        public Instant getNow() {
            return ctx.now();
        }
    }
}
