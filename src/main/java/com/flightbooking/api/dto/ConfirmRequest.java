package com.flightbooking.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Payment payload passed with POST /booking/confirm/{bookingId}. */
public record ConfirmRequest(
        @NotBlank String paymentMethod
) {}
