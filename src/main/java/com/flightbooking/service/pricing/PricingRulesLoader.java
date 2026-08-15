package com.flightbooking.service.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightbooking.service.pricing.config.PricingRuleEntry;
import com.flightbooking.service.pricing.config.PricingRulesConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Reads the pricing rules JSON at startup, compiles each rule's SpEL, and
 * hands {@link PricingService} a ready-to-fold list of strategies.
 *
 * <p><b>Only one strategy type exists.</b> Everything the policy team wants
 * to price on — base fare, demand curves, time-to-departure, taxes, route
 * surcharges — is an {@code "expression"} rule with a SpEL formula. Java
 * owns the runtime; policy owns the calculation.</p>
 *
 * <p>The {@link #BUILDERS} map is still here so future non-expression rule
 * kinds (e.g. a compiled-Java escape hatch for logic SpEL can't express)
 * slot in with one line, and so the loader fails loud on {@code "type"}
 * typos rather than silently ignoring an entry.</p>
 *
 * <p><b>Fail-loud at boot.</b> Missing file, empty file, unknown type,
 * missing formula, or a malformed SpEL expression all crash the app with a
 * precise message naming the offending entry. Silently pricing at 1.0x
 * forever is not a failure mode.</p>
 *
 * <p>Location defaults to {@code classpath:pricing-rules.json} but can be
 * overridden with {@code app.pricing.rules-location} — point it at a mounted
 * ConfigMap or S3-synced file for a policy-team-owned external drop.</p>
 */
@Slf4j
@Component
public class PricingRulesLoader {

    /**
     * The one place that names every built-in strategy type. Currently a
     * single entry — expression covers every pricing rule we ship. Adding
     * a new kind = one new line here + one new class file with the same
     * {@code static from(PricingRuleEntry)} shape.
     */
    private static final Map<String, Function<PricingRuleEntry, PriceStrategy>> BUILDERS = Map.of(
            ExpressionStrategy.TYPE, ExpressionStrategy::from
    );

    private final List<PriceStrategy> strategies;

    public PricingRulesLoader(
            @Value("${app.pricing.rules-location:classpath:pricing-rules.json}") String location,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper) {

        this.strategies = loadStrategies(location, resourceLoader, objectMapper);
        log.info("PricingRulesLoader active: {} strategies from {} (supported types: {}) -> {}",
                strategies.size(), location,
                new TreeSet<>(BUILDERS.keySet()),
                strategies.stream().map(PriceStrategy::name).toList());
    }

    /** Ordered list of strategies, ready to be folded over the running price. */
    public List<PriceStrategy> getStrategies() {
        return strategies;
    }

    private static List<PriceStrategy> loadStrategies(String location,
                                                      ResourceLoader resourceLoader,
                                                      ObjectMapper objectMapper) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Pricing rules file not found at " + location
                    + " — set app.pricing.rules-location or add pricing-rules.json to the classpath");
        }

        PricingRulesConfig config;
        try (InputStream in = resource.getInputStream()) {
            config = objectMapper.readValue(in, PricingRulesConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse pricing rules from " + location, e);
        }

        List<PricingRuleEntry> entries = Objects.requireNonNullElse(config.strategies(), List.<PricingRuleEntry>of());
        if (entries.isEmpty()) {
            throw new IllegalStateException(
                    "Pricing rules file " + location + " contains no strategies — "
                    + "at least one expression rule (e.g. a base-fare seed) is required, "
                    + "else every quote is 0.");
        }

        return entries.stream()
                .sorted(Comparator.comparingInt(PricingRuleEntry::order))
                .map(PricingRulesLoader::dispatch)
                .toList();
    }

    /**
     * Look up the {@code from} function for this entry's {@code type} and call
     * it. Each strategy validates its own required fields inside {@code from}.
     */
    private static PriceStrategy dispatch(PricingRuleEntry entry) {
        if (entry.type() == null || entry.type().isBlank()) {
            throw new IllegalStateException(
                    "Pricing rule entry '" + entry.displayName() + "' is missing required field 'type'");
        }
        Function<PricingRuleEntry, PriceStrategy> builder = BUILDERS.get(entry.type());
        if (builder == null) {
            throw new IllegalStateException(
                    "Unknown pricing rule type '" + entry.type() + "' for entry '"
                    + entry.displayName() + "' — supported types: "
                    + new TreeSet<>(BUILDERS.keySet()));
        }
        return builder.apply(entry);
    }
}
