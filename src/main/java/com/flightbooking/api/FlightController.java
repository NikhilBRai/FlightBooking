package com.flightbooking.api;

import com.flightbooking.api.dto.FlightDetailsDto;
import com.flightbooking.api.dto.ItineraryDto;
import com.flightbooking.domain.enums.SortBy;
import com.flightbooking.service.FlightService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/flights")
@RequiredArgsConstructor
public class FlightController {

    private final FlightService flightService;

    /**
     * Search for trips.
     * <pre>
     * GET /flights?source=BLR&destination=BOM&date=2026-08-20&maxStops=1&sort=CHEAPEST
     * </pre>
     *
     * <p>Returns a list of {@link ItineraryDto}. Each itinerary is one segment
     * (direct) or two (one-stop). {@code maxStops=0} restricts results to
     * direct flights; {@code maxStops>=1} (or unset) includes one-stop
     * itineraries stitched from two point-to-point flights.</p>
     */
    @GetMapping
    public List<ItineraryDto> search(
            @RequestParam String source,
            @RequestParam String destination,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer maxStops,
            @RequestParam(required = false, defaultValue = "CHEAPEST") SortBy sort) {
        return flightService.searchFlights(source, destination, date, maxStops, sort);
    }

    /** GET /flights/{flightId} — full details including seat map with statuses. */
    @GetMapping("/{flightId}")
    public FlightDetailsDto details(@PathVariable Long flightId) {
        return flightService.getFlightDetails(flightId);
    }
}
