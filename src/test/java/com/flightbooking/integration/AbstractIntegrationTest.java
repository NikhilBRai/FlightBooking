package com.flightbooking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.domain.entity.Seat;
import com.flightbooking.domain.entity.User;
import com.flightbooking.repository.BookingRepository;
import com.flightbooking.repository.FlightModelRepository;
import com.flightbooking.repository.FlightRepository;
import com.flightbooking.repository.FlightSeatRepository;
import com.flightbooking.repository.ItineraryRepository;
import com.flightbooking.repository.PaymentRepository;
import com.flightbooking.repository.SeatRepository;
import com.flightbooking.repository.UserRepository;
import com.flightbooking.repository.WaitlistRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Base class for every full-stack integration test. Boots the whole
 * app on H2 with the in-memory seat-lock backend (test profile), lets
 * subclasses hit real HTTP via MockMvc, and wipes every mutable table
 * between tests so ordering doesn't matter and idempotency keys
 * don't leak across cases.
 *
 * <p>@ActiveProfiles("test") is what disables DataSeeder — each test
 * seeds only what it needs, so nothing here depends on the prod-y
 * "Alice / Boeing / BLR→BOM" fixture graph.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;

    @Autowired protected UserRepository userRepository;
    @Autowired protected FlightModelRepository flightModelRepository;
    @Autowired protected SeatRepository seatRepository;
    @Autowired protected FlightRepository flightRepository;
    @Autowired protected ItineraryRepository itineraryRepository;
    @Autowired protected BookingRepository bookingRepository;
    @Autowired protected FlightSeatRepository flightSeatRepository;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected WaitlistRepository waitlistRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * Wipe every mutable table between tests. Uses raw SQL through
     * {@link JdbcTemplate} instead of {@code JpaRepository.deleteAllInBatch}
     * for two reasons:
     *
     * <ol>
     *   <li><b>Cyclic FK</b>:
     *       {@code itineraries.payment_id → payments.id} and
     *       {@code payments.itinerary_id → itineraries.id} form a
     *       cycle, so neither table can be deleted first while the
     *       other still holds rows. We break the cycle by nulling
     *       the nullable side ({@code itineraries.payment_id})
     *       before deleting either.</li>
     *   <li><b>Speed</b>: a single UPDATE + N DELETEs is dramatically
     *       cheaper than N JpaRepository round-trips through the
     *       Hibernate session cache.</li>
     * </ol>
     *
     * <p>The DELETE order below is still child → parent for the
     * remaining (non-cyclic) FKs. Portable across H2 and Postgres —
     * no {@code SET REFERENTIAL_INTEGRITY FALSE} tricks.</p>
     */
    @AfterEach
    void wipe() {
        jdbcTemplate.update("UPDATE itineraries SET payment_id = NULL");
        jdbcTemplate.update("DELETE FROM payments");
        jdbcTemplate.update("DELETE FROM flight_seats");
        jdbcTemplate.update("DELETE FROM waitlist");
        jdbcTemplate.update("DELETE FROM bookings");
        jdbcTemplate.update("DELETE FROM itineraries");
        jdbcTemplate.update("DELETE FROM flights");
        jdbcTemplate.update("DELETE FROM seats");
        jdbcTemplate.update("DELETE FROM flight_models");
        jdbcTemplate.update("DELETE FROM users");
    }

    // ---- Fixture helpers ---------------------------------------------

    protected User createUser(String name, String email) {
        return userRepository.save(User.builder().name(name).email(email).build());
    }

    protected FlightModel createModel(String make, int totalSeats) {
        return flightModelRepository.save(FlightModel.builder().make(make).totalSeats(totalSeats).build());
    }

    protected Seat createSeat(FlightModel model, String seatNumber) {
        return seatRepository.save(Seat.builder().flightModel(model).seatNumber(seatNumber).build());
    }

    protected Flight createFlight(FlightModel model, String src, String dst,
                                  Instant start, Duration dur, BigDecimal cost) {
        return flightRepository.save(Flight.builder()
                .flightModel(model).source(src).destination(dst)
                .startTime(start).endTime(start.plus(dur))
                .cost(cost).fullyBooked(false)
                .build());
    }
}
