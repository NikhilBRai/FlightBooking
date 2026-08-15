package com.flightbooking.api.dto;

import lombok.Builder;

import java.util.List;

/** Full flight details returned by GET /flights/{flightId} — includes seat map. */
@Builder
public record FlightDetailsDto(
        FlightSummaryDto flight,
        List<SeatDto> seats
) {}
