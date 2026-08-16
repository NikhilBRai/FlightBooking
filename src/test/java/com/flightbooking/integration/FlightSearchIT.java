package com.flightbooking.integration;

import com.flightbooking.api.ItineraryController;
import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
import com.flightbooking.domain.enums.PaymentMethod;
import com.flightbooking.api.dto.LegRequest;
import com.flightbooking.api.dto.ReserveRequest;

import java.util.List;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the read APIs end-to-end: {@code GET /flights} for
 * multi-hop search + sort, and {@code GET /flights/{id}} for the seat
 * map with BOOKED / LOCKED / AVAILABLE overlay.
 */
class FlightSearchIT extends AbstractIntegrationTest {

    private User alice;
    private FlightModel model;
    private Flight direct;
    private Flight leg1;
    private Flight leg2;
    private Seat seat1;
    private Seat seat2;
    private Seat seat3;
    private LocalDate searchDate;

    @BeforeEach
    void seed() {
        alice = createUser("Alice", "a@e");
        model = createModel("Boeing", 3);
        seat1 = createSeat(model, "1A");
        seat2 = createSeat(model, "1B");
        seat3 = createSeat(model, "2A");

        // Departure day is 30 days from now to stay comfortably ahead of any
        // pricing rule's hoursToDeparture windows.
        Instant departure = Instant.now().plus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        searchDate = LocalDate.ofInstant(departure, ZoneOffset.UTC);

        direct = createFlight(model, "BLR", "BOM",
                departure.plus(Duration.ofHours(6)), Duration.ofHours(2), new BigDecimal("3200"));
        leg1 = createFlight(model, "BLR", "HYD",
                departure.plus(Duration.ofHours(8)), Duration.ofMinutes(90), new BigDecimal("2200"));
        leg2 = createFlight(model, "HYD", "BOM",
                departure.plus(Duration.ofHours(12)), Duration.ofMinutes(90), new BigDecimal("1900"));
    }

    // ---- search -------------------------------------------------------

    @Test
    void search_returnsBothDirectAndOneStopItineraries() throws Exception {
        mvc.perform(get("/flights")
                        .param("source", "BLR")
                        .param("destination", "BOM")
                        .param("date", searchDate.toString())
                        .param("maxStops", "1")
                        .param("sort", "CHEAPEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$[?(@.stops == 0)]").exists())
                .andExpect(jsonPath("$[?(@.stops == 1)]").exists());
    }

    @Test
    @DisplayName("maxStops=0 restricts to direct itineraries only")
    void search_maxStopsZeroReturnsDirectOnly() throws Exception {
        mvc.perform(get("/flights")
                        .param("source", "BLR")
                        .param("destination", "BOM")
                        .param("date", searchDate.toString())
                        .param("maxStops", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].stops").value(0));
    }

    @Test
    @DisplayName("invalid date param → 400 (type mismatch handler)")
    void search_invalidDateIs400() throws Exception {
        mvc.perform(get("/flights")
                        .param("source", "BLR")
                        .param("destination", "BOM")
                        .param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    // ---- details ------------------------------------------------------

    @Test
    void details_unknownFlightIs404() throws Exception {
        mvc.perform(get("/flights/9999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("seat map reflects mixed BOOKED / LOCKED / AVAILABLE state")
    void details_seatMapReflectsMixedState() throws Exception {
        // Reserve (LOCKS) seat2 for Alice, confirm (BOOKS) seat1.
        String k1 = UUID.randomUUID().toString();
        reserveAndConfirm(alice, k1, seat1.getId());
        String k2 = UUID.randomUUID().toString();
        MvcResult res = mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, alice.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, k2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1.getId(), seat2.getId()))))))
                .andExpect(status().isOk())
                .andReturn();
        assert res.getResponse().getStatus() == 200;

        mvc.perform(get("/flights/" + leg1.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[?(@.seatNumber == '1A')].status").value("BOOKED"))
                .andExpect(jsonPath("$.seats[?(@.seatNumber == '1B')].status").value("LOCKED"))
                .andExpect(jsonPath("$.seats[?(@.seatNumber == '2A')].status").value("AVAILABLE"));
    }

    private Long reserveAndConfirm(User u, String idem, Long seatId) throws Exception {
        MvcResult rr = mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, u.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(leg1.getId(), seatId))))))
                .andExpect(status().isOk())
                .andReturn();
        BookingItineraryDto resp = mapper.readValue(
                rr.getResponse().getContentAsString(), BookingItineraryDto.class);
        mvc.perform(post("/itinerary/" + resp.itineraryId() + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, u.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ConfirmRequest(PaymentMethod.CARD))))
                .andExpect(status().isOk());
        return resp.itineraryId();
    }
}
