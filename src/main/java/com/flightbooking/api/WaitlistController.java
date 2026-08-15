package com.flightbooking.api;

import com.flightbooking.api.dto.WaitlistEntryDto;
import com.flightbooking.domain.entity.WaitlistEntry;
import com.flightbooking.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Waitlist endpoints scoped to a flight. When a seat on this flight
 * is later cancelled, every user on this list is notified in FIFO
 * join order and left on the list — they race a fresh reserve, first
 * to grab the Redis lock wins, and losers stay on the waitlist so
 * they'll be told about the next opening too.
 *
 * <p>Identity, as with the booking endpoints, comes from the
 * {@code X-User-Id} header (real deployment would decode it from a
 * verified JWT; this project skips the auth filter).</p>
 */
@RestController
@RequestMapping("/flights/{flightId}/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    /**
     * {@code POST /flights/{flightId}/waitlist} — add self to the
     * flight's waitlist.
     *
     * <p>Behaviour:</p>
     * <ul>
     *   <li>{@code 200} — newly added, or already on the list
     *       (idempotent); DTO is identical in both cases.</li>
     *   <li>{@code 400} — missing {@code X-User-Id} header.</li>
     *   <li>{@code 404} — no flight with that id, or unknown user.</li>
     *   <li>{@code 409} — the user already has a
     *       {@code RESERVED}/{@code CONFIRMED} booking on this
     *       flight, so there's nothing to wait for.</li>
     * </ul>
     */
    @PostMapping
    public WaitlistEntryDto join(@PathVariable Long flightId,
                                 @RequestHeader(ItineraryController.USER_ID_HEADER) Long userId) {
        WaitlistEntry entry = waitlistService.addToWaitlist(userId, flightId);
        return toDto(entry);
    }

    /**
     * {@code DELETE /flights/{flightId}/waitlist} — leave the
     * flight's waitlist. Idempotent: {@code 204 No Content} whether
     * you were on the list or not, so a client can safely call it
     * without a "were you on the list?" pre-check.
     */
    @DeleteMapping
    public ResponseEntity<Void> leave(@PathVariable Long flightId,
                                      @RequestHeader(ItineraryController.USER_ID_HEADER) Long userId) {
        waitlistService.removeFromWaitlist(userId, flightId);
        return ResponseEntity.noContent().build();
    }

    private static WaitlistEntryDto toDto(WaitlistEntry entry) {
        return WaitlistEntryDto.builder()
                .waitlistId(entry.getId())
                .userId(entry.getUser().getId())
                .flightId(entry.getFlight().getId())
                .addedAt(entry.getAddedAt())
                .build();
    }
}
