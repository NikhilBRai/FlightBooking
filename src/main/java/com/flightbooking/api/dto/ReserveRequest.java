package com.flightbooking.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for {@code POST /itinerary/reserve}. The caller's user id is
 * <b>not</b> in the body — it comes from the {@code X-User-Id}
 * request header instead. In a real deployment that value would be
 * extracted from a verified JWT by an auth filter; this project
 * skips the auth layer and reads the header directly.
 *
 * <p>The contract is uniform across direct and multi-leg trips: a
 * direct flight is {@code legs} with one entry, a two-hop trip is
 * two entries in order (leg 0 first flown, leg 1 second), etc. The
 * server sorts the legs internally for deadlock-free seat locking
 * but preserves the caller's order for the {@code legOrder}
 * assignment on the persisted {@code Booking} rows.</p>
 */
public record ReserveRequest(
        @NotEmpty(message = "legs must contain at least one entry")
        @Size(max = MAX_LEGS,
                message = "itinerary cannot exceed " + MAX_LEGS + " legs")
        @Valid
        List<LegRequest> legs
) {
    /**
     * Real-world itineraries are almost always 1–4 legs (a round-trip
     * with one stop each way is 4). We cap at 8 to give plenty of
     * headroom for pathological cases while still refusing obviously
     * absurd requests (e.g. a fuzzed 500-leg body). If your business
     * ever legitimately sells 9-leg itineraries, bump this here.
     */
    public static final int MAX_LEGS = 8;
}
