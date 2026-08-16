package com.flightbooking.domain.enums;

/**
 * Payment instrument the caller wants to use on
 * {@code POST /itinerary/{id}/confirm}. Typed at the API boundary
 * (bound from JSON by Jackson via {@code @NotNull PaymentMethod}) so
 * {@code paymentMethod: "monopoly-money"} is rejected as a 400 before
 * it ever reaches the payment service.
 *
 * <p>Stored on {@code payments.payment_method} via
 * {@code @Enumerated(EnumType.STRING)} — column stays a plain
 * {@code VARCHAR} so schema evolution across environments (H2 dev
 * DB, Postgres prod DB) doesn't need a bespoke enum type.</p>
 *
 * <p>Add a new value here <em>only</em> once the downstream gateway
 * integration can actually settle it — clients will see it exposed
 * on the enum values list immediately.</p>
 */
public enum PaymentMethod {
    CARD,
    UPI,
    WALLET,
    NETBANKING
}
