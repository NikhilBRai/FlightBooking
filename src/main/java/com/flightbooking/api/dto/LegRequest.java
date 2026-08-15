package com.flightbooking.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * One leg of a reserve request — a {@code (flightId, seatId)} pair.
 * A direct-flight reservation is a size-1 {@code legs} array with a
 * single entry; a two-hop trip is size-2, and so on.
 */
public record LegRequest(
        @NotNull Long flightId,
        @NotNull Long seatId
) {}
