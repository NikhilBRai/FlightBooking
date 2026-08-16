package com.flightbooking.api.dto;

import com.flightbooking.domain.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;

/**
 * Payment payload for {@code POST /itinerary/{itineraryId}/confirm}.
 * {@link PaymentMethod} is a closed enum so an unknown value
 * ({@code "monopoly-money"}, an empty string, {@code null}) is
 * rejected as {@code 400} at binding time and never reaches the
 * payment service.
 */
public record ConfirmRequest(
        @NotNull PaymentMethod paymentMethod
) {}
