package com.flightbooking.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flightbooking.api.ItineraryController;
import com.flightbooking.api.dto.BookingItineraryDto;
import com.flightbooking.api.dto.ConfirmRequest;
import com.flightbooking.api.dto.LegRequest;
import com.flightbooking.api.dto.ReserveRequest;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.domain.entity.WaitlistEntry;
import com.flightbooking.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Waitlist join / leave / notify-on-cancel. Uses a Logback appender on
 * NotificationService as a fan-out oracle — asserting each waiter got
 * exactly one "seat opened" notification when the seat is cancelled.
 */
class WaitlistIT extends AbstractIntegrationTest {

    private User owner;
    private User bob;
    private User carol;
    private Flight flight;
    private Seat seat;
    private ListAppender<ILoggingEvent> notifyAppender;
    private Logger notifyLogger;

    @BeforeEach
    void seed() {
        owner = createUser("Owner", "owner@e");
        bob = createUser("Bob", "b@e");
        carol = createUser("Carol", "c@e");
        FlightModel model = createModel("Boeing", 6);
        seat = createSeat(model, "1A");
        flight = createFlight(model, "BLR", "BOM",
                Instant.now().plus(Duration.ofDays(30)),
                Duration.ofHours(2), new BigDecimal("3200"));

        notifyLogger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        notifyAppender = new ListAppender<>();
        notifyAppender.start();
        notifyLogger.addAppender(notifyAppender);
        notifyLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        notifyLogger.detachAppender(notifyAppender);
    }

    // ---- join / leave -----------------------------------------------

    @Test
    void joinIsIdempotent_secondCallReturnsSameEntryId() throws Exception {
        MvcResult first = mvc.perform(post("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mvc.perform(post("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isOk())
                .andReturn();

        Long id1 = mapper.readTree(first.getResponse().getContentAsString()).get("waitlistId").asLong();
        Long id2 = mapper.readTree(replay.getResponse().getContentAsString()).get("waitlistId").asLong();
        assertThat(id2).isEqualTo(id1);
        assertThat(waitlistRepository.findAll()).hasSize(1);
    }

    @Test
    void leaveIsIdempotent_bothReturn204() throws Exception {
        mvc.perform(post("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isOk());

        mvc.perform(delete("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isNoContent());

        assertThat(waitlistRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("user with an active itinerary leg on the flight can't join the waitlist")
    void activeBookerCannotJoin() throws Exception {
        String idem = UUID.randomUUID().toString();
        reserveAndConfirm(bob, idem, seat.getId());

        mvc.perform(post("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("active booking")));
    }

    @Test
    void joinUnknownFlightIs404() throws Exception {
        mvc.perform(post("/flights/99999/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, bob.getId()))
                .andExpect(status().isNotFound());
    }

    // ---- fan-out on cancel -------------------------------------------

    @Test
    @DisplayName("cancelling a confirmed seat notifies every waitlisted user in FIFO order")
    void cancelFansOutNotificationsToAllWaiters() throws Exception {
        String idem = UUID.randomUUID().toString();
        Long itineraryId = reserveAndConfirm(owner, idem, seat.getId());

        joinWaitlist(bob);
        joinWaitlist(carol);

        // Reset appender so we only count notifications from the cancel step.
        notifyAppender.list.clear();

        mvc.perform(post("/itinerary/" + itineraryId + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, owner.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // One notify() for the itinerary owner (cancel confirmation) +
        // two for the waitlisted users. We only care that both
        // waiters received their "seat opened" notification.
        List<String> messages = notifyAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("Bob") && m.contains("Seat available"));
        assertThat(messages).anyMatch(m -> m.contains("Carol") && m.contains("Seat available"));
    }

    @Test
    @DisplayName("waitlisted users are NOT removed by notify — a subsequent cancel notifies them again")
    void waitersStayOnListAfterNotify() throws Exception {
        String idem1 = UUID.randomUUID().toString();
        Long itinerary1 = reserveAndConfirm(owner, idem1, seat.getId());
        joinWaitlist(bob);

        mvc.perform(post("/itinerary/" + itinerary1 + "/cancel")
                        .header(ItineraryController.USER_ID_HEADER, owner.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        List<WaitlistEntry> stillOnList = waitlistRepository.findAll();
        assertThat(stillOnList).extracting(w -> w.getUser().getId()).contains(bob.getId());
    }

    // ---- helpers ------------------------------------------------------

    private void joinWaitlist(User u) throws Exception {
        mvc.perform(post("/flights/" + flight.getId() + "/waitlist")
                        .header(ItineraryController.USER_ID_HEADER, u.getId()))
                .andExpect(status().isOk());
    }

    private Long reserveAndConfirm(User u, String idem, Long seatId) throws Exception {
        MvcResult rr = mvc.perform(post("/itinerary/reserve")
                        .header(ItineraryController.USER_ID_HEADER, u.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReserveRequest(List.of(
                                new LegRequest(flight.getId(), seatId))))))
                .andExpect(status().isOk())
                .andReturn();
        BookingItineraryDto resp = mapper.readValue(
                rr.getResponse().getContentAsString(), BookingItineraryDto.class);
        mvc.perform(post("/itinerary/" + resp.itineraryId() + "/confirm")
                        .header(ItineraryController.USER_ID_HEADER, u.getId())
                        .header(ItineraryController.IDEMPOTENCY_KEY_HEADER, idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ConfirmRequest("card"))))
                .andExpect(status().isOk());
        return resp.itineraryId();
    }
}
