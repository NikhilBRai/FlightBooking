package com.flightbooking.config;

import com.flightbooking.domain.entity.*;
import com.flightbooking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds users, aircraft models, seat templates and flights so the API is
 * exercisable right after startup. Notably does <em>not</em> pre-seed
 * flight_seats — that table is sparse (one row per booked seat only), so
 * every flight starts with zero rows there.
 *
 * <p>Every seeded flight is a direct point-to-point segment. The BLR → HYD →
 * BOM path exists specifically so the two-hop search can prove itself in a
 * smoke test.</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final FlightModelRepository flightModelRepository;
    private final SeatRepository seatRepository;
    private final FlightRepository flightRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (flightRepository.count() > 0) {
            log.info("DataSeeder: existing data detected, skipping seed");
            return;
        }
        log.info("DataSeeder: seeding sample data...");

        User alice = userRepository.save(User.builder().name("Alice").email("alice@example.com").build());
        User bob   = userRepository.save(User.builder().name("Bob").email("bob@example.com").build());
        User carol = userRepository.save(User.builder().name("Carol").email("carol@example.com").build());

        FlightModel boeing737 = flightModelRepository.save(
                FlightModel.builder().make("Boeing 737-800").totalSeats(6).build());
        FlightModel airbusA320 = flightModelRepository.save(
                FlightModel.builder().make("Airbus A320").totalSeats(6).build());

        createSeats(boeing737, List.of("1A", "1B", "2A", "2B", "3A", "3B"));
        createSeats(airbusA320, List.of("1A", "1B", "2A", "2B", "3A", "3B"));

        LocalDate day = LocalDate.now().plusDays(1);

        // Direct BLR→BOM options
        Flight blrBom0800 = createFlight(boeing737, "BLR", "BOM",
                day.atTime(8, 0).toInstant(ZoneOffset.UTC),
                Duration.ofHours(2), new BigDecimal("4500.00"));
        Flight blrBom1400 = createFlight(airbusA320, "BLR", "BOM",
                day.atTime(14, 0).toInstant(ZoneOffset.UTC),
                Duration.ofHours(2).plusMinutes(30), new BigDecimal("3200.00"));

        // BLR → HYD → BOM path (search should stitch these into a 1-stop itinerary):
        //   leg 1: BLR→HYD dep 09:00, dur 1h30m → arr HYD 10:30
        //   leg 2: HYD→BOM dep 12:30 (2h layover, well above min 60m), dur 1h30m → arr BOM 14:00
        Flight blrHyd0900 = createFlight(boeing737, "BLR", "HYD",
                day.atTime(9, 0).toInstant(ZoneOffset.UTC),
                Duration.ofHours(1).plusMinutes(30), new BigDecimal("2200.00"));
        Flight hydBom1230 = createFlight(airbusA320, "HYD", "BOM",
                day.atTime(12, 30).toInstant(ZoneOffset.UTC),
                Duration.ofHours(1).plusMinutes(30), new BigDecimal("1900.00"));

        // BLR → MAA → HYD → BOM path (search should stitch these into a 2-stop itinerary):
        //   leg 1: BLR→MAA dep 07:00 → arr MAA 08:30
        //   leg 2: MAA→HYD dep 10:00 (1h30m layover at MAA) → arr HYD 11:00
        //   leg 3: HYD→BOM dep 12:30 (1h30m layover at HYD, reuses hydBom1230) → arr BOM 14:00
        Flight blrMaa0700 = createFlight(boeing737, "BLR", "MAA",
                day.atTime(7, 0).toInstant(ZoneOffset.UTC),
                Duration.ofHours(1).plusMinutes(30), new BigDecimal("1800.00"));
        Flight maaHyd1000 = createFlight(airbusA320, "MAA", "HYD",
                day.atTime(10, 0).toInstant(ZoneOffset.UTC),
                Duration.ofHours(1), new BigDecimal("1500.00"));

        // Unrelated but useful for exercising the details endpoint / other routes.
        Flight bomHyd1000 = createFlight(boeing737, "BOM", "HYD",
                day.atTime(10, 0).toInstant(ZoneOffset.UTC),
                Duration.ofHours(1).plusMinutes(30), new BigDecimal("2800.00"));

        log.info("DataSeeder: seeded users={} flights={} (flight_seats starts empty)",
                List.of(alice.getId(), bob.getId(), carol.getId()),
                List.of(blrBom0800.getId(), blrBom1400.getId(),
                        blrHyd0900.getId(), hydBom1230.getId(),
                        blrMaa0700.getId(), maaHyd1000.getId(),
                        bomHyd1000.getId()));
    }

    private List<Seat> createSeats(FlightModel model, List<String> numbers) {
        List<Seat> saved = new ArrayList<>();
        for (String n : numbers) {
            saved.add(seatRepository.save(
                    Seat.builder().flightModel(model).seatNumber(n).build()));
        }
        return saved;
    }

    private Flight createFlight(FlightModel model, String src, String dst,
                                Instant startTime, Duration duration,
                                BigDecimal cost) {
        return flightRepository.save(Flight.builder()
                .flightModel(model)
                .source(src)
                .destination(dst)
                .startTime(startTime)
                .endTime(startTime.plus(duration))
                .cost(cost)
                .fullyBooked(false)
                .build());
    }
}
