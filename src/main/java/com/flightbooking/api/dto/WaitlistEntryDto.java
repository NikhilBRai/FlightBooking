package com.flightbooking.api.dto;

import lombok.Builder;

import java.time.Instant;

/**
 * Response for {@code POST /flights/{flightId}/waitlist}. Returned
 * whether the caller was newly added or was already on the list —
 * the join endpoint is idempotent, so the DTO is the same in both
 * cases and the caller can treat any 200 as "you're on the waitlist
 * now."
 */
@Builder
public record WaitlistEntryDto(
        Long waitlistId,
        Long userId,
        Long flightId,
        Instant addedAt
) {}
