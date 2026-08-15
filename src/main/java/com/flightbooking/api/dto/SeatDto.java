package com.flightbooking.api.dto;

import com.flightbooking.domain.enums.SeatStatus;
import lombok.Builder;

@Builder
public record SeatDto(
        Long seatId,
        String seatNumber,
        SeatStatus status
) {}
