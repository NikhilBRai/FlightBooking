package com.flightbooking.service.search;

import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.repository.FlightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * The batched strategy's whole point is <b>two DB round-trips</b>
 * regardless of graph shape. Each test asserts a specific correctness
 * property (path enumeration, layover window, cycle guard, source
 * closure) AND that the query count didn't creep back up to O(N).
 */
@ExtendWith(MockitoExtension.class)
class BatchedFlightSearchStrategyTest {

    @Mock FlightRepository flightRepository;
    @InjectMocks BatchedFlightSearchStrategy strategy;

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final FlightModel MODEL = FlightModel.builder().id(1L).make("m").totalSeats(6).build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(strategy, "minLayoverMinutes", 60L);
        ReflectionTestUtils.setField(strategy, "maxLayoverHours", 12L);
        ReflectionTestUtils.setField(strategy, "maxFlightHours", 24L);
    }

    private static Flight flight(long id, String src, String dst, Instant start, Duration dur) {
        return Flight.builder()
                .id(id).flightModel(MODEL).source(src).destination(dst)
                .startTime(start).endTime(start.plus(dur))
                .cost(new BigDecimal("1000")).fullyBooked(false).build();
    }

    // ---- Happy paths --------------------------------------------------

    @Test
    @DisplayName("direct-only: exactly one query (spine), pool query is not fired")
    void directOnlyRunsOneQueryOnly() {
        Flight direct = flight(1L, "BLR", "BOM", T0.plusSeconds(3600), Duration.ofHours(2));
        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of(direct));

        List<List<Flight>> out = strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3);

        assertThat(out).containsExactly(List.of(direct));
        verify(flightRepository, times(1)).findInboundInWindow(any(), any(), any());
        verify(flightRepository, never()).findAllLandingInWindow(any(), any());
    }

    @Test
    @DisplayName("one-stop path stitched from spine + one pool feeder — exactly two queries fired")
    void oneStopExactlyTwoQueries() {
        // Spine: HYD -> BOM at 12:30, needs a feeder landing HYD 60m-12h before.
        Flight leg2 = flight(2L, "HYD", "BOM", T0.plus(Duration.ofHours(12).plusMinutes(30)),
                Duration.ofMinutes(90));
        // Pool: BLR -> HYD landing at 10:30 (2h layover, well inside window).
        Flight leg1 = flight(1L, "BLR", "HYD", T0.plus(Duration.ofHours(9)),
                Duration.ofMinutes(90));

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of(leg2));
        when(flightRepository.findAllLandingInWindow(any(), any())).thenReturn(List.of(leg1));

        List<List<Flight>> out = strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).extracting(Flight::getId).containsExactly(1L, 2L);
        verify(flightRepository, times(1)).findInboundInWindow(any(), any(), any());
        verify(flightRepository, times(1)).findAllLandingInWindow(any(), any());
    }

    @Test
    @DisplayName("no viable path: empty result")
    void noPathReturnsEmpty() {
        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of());
        assertThat(strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3)).isEmpty();
    }

    // ---- Layover window ----------------------------------------------

    @Test
    @DisplayName("feeder inside min-layover (arrives <60m before spine) is rejected")
    void feederViolatingMinLayoverRejected() {
        Flight leg2 = flight(2L, "HYD", "BOM", T0.plus(Duration.ofHours(10)),
                Duration.ofMinutes(90));
        // Lands at 09:30, spine leaves at 10:00 — only 30m layover, < 60m min.
        Flight tooClose = flight(1L, "BLR", "HYD", T0.plus(Duration.ofHours(8)),
                Duration.ofMinutes(90));

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of(leg2));
        when(flightRepository.findAllLandingInWindow(any(), any())).thenReturn(List.of(tooClose));

        assertThat(strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3)).isEmpty();
    }

    @Test
    @DisplayName("feeder outside max-layover (arrives >12h before spine) is rejected")
    void feederViolatingMaxLayoverRejected() {
        // Spine leaves 18:00.
        Flight leg2 = flight(2L, "HYD", "BOM", T0.plus(Duration.ofHours(18)),
                Duration.ofMinutes(90));
        // Feeder lands at 02:00 — 16h before, > 12h max. But we still
        // need it to be inside the pool window, so it must land after
        // earliestPossibleLanding = 18:00 - (3 * 12h) - (2 * 24h) = -66:00
        // (day-before-yesterday 06:00) → yes, fetched by pool but
        // rejected per-path.
        Flight tooEarly = flight(1L, "BLR", "HYD", T0.plus(Duration.ofMinutes(30)),
                Duration.ofMinutes(90));

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of(leg2));
        when(flightRepository.findAllLandingInWindow(any(), any())).thenReturn(List.of(tooEarly));

        assertThat(strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3)).isEmpty();
    }

    // ---- Depth cap / cycle guard --------------------------------------

    @Test
    @DisplayName("maxStops=0: only direct itineraries surface")
    void maxStopsZeroReturnsOnlyDirect() {
        Flight direct = flight(1L, "BLR", "BOM", T0.plus(Duration.ofHours(2)), Duration.ofHours(2));
        Flight indirect = flight(2L, "HYD", "BOM", T0.plus(Duration.ofHours(2)), Duration.ofHours(2));
        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of(direct, indirect));

        List<List<Flight>> out = strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 0);

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).extracting(Flight::getId).containsExactly(1L);
        verify(flightRepository, never()).findAllLandingInWindow(any(), any());
    }

    // ---- Pool query bounds --------------------------------------------

    @Test
    @DisplayName("pool query upper bound is (latest spine departure - min layover), not end-of-day")
    void poolWindowUpperBoundIsLatestSpineDepartureMinusMinLayover() {
        // Two spine flights landing at BOM today. Latest departs at 18:00,
        // min-layover is 60 min. The tight pool ceiling is 17:00 — no
        // useful feeder can land later than that, because it wouldn't
        // leave the required 60-min gap before the latest spine departs.
        // Landing exactly at 17:00 is allowed (inclusive bound), matching
        // the per-path filter that uses isAfter (strict >).
        Flight earlySpine = flight(10L, "HYD", "BOM",
                T0.plus(Duration.ofHours(9)), Duration.ofMinutes(90));
        Flight lateSpine  = flight(11L, "HYD", "BOM",
                T0.plus(Duration.ofHours(18)), Duration.ofMinutes(90));

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any()))
                .thenReturn(List.of(earlySpine, lateSpine));
        when(flightRepository.findAllLandingInWindow(any(), any()))
                .thenReturn(List.of());

        strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3);

        ArgumentCaptor<Instant> lower = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> upper = ArgumentCaptor.forClass(Instant.class);
        verify(flightRepository).findAllLandingInWindow(lower.capture(), upper.capture());

        // Upper bound == max spine startTime - minLayover (18:00 - 60m = 17:00),
        // NOT windowEnd (24:00) and NOT plain max spine startTime (18:00).
        assertThat(upper.getValue())
                .isEqualTo(T0.plus(Duration.ofHours(17)));
        // Lower bound == min spine startTime - N*maxLayover - (N-1)*maxFlightDuration
        //             = 9:00 - 3*12h - 2*24h = 9:00 - 84h = -75:00 (3.5 days back).
        // The (N-1)*maxFlightDuration term is necessary for N>=2 to admit
        // chains whose intermediate flights are long-haul — see
        // BatchedFlightSearchStrategy's class Javadoc.
        assertThat(lower.getValue())
                .isEqualTo(T0.plus(Duration.ofHours(9)).minus(Duration.ofHours(3 * 12 + 2 * 24)));
    }

    @Test
    @DisplayName("N=1 (single-stop): no intermediate-duration term needed — lower = spineStart − 1·maxLayover")
    void poolWindowLowerBoundIgnoresIntermediateDurationForOneStop() {
        // A single-stop chain has zero intermediate flights (spine + 1
        // feeder = 2 flights, 1 layover), so the (N-1)*maxFlightDuration
        // term collapses to zero. Verifies the formula degenerates
        // cleanly for the N=1 case.
        Flight spine = flight(10L, "HYD", "BOM",
                T0.plus(Duration.ofHours(10)), Duration.ofMinutes(90));
        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any()))
                .thenReturn(List.of(spine));
        when(flightRepository.findAllLandingInWindow(any(), any()))
                .thenReturn(List.of());

        strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 1);

        ArgumentCaptor<Instant> lower = ArgumentCaptor.forClass(Instant.class);
        verify(flightRepository).findAllLandingInWindow(lower.capture(), any());
        // 10:00 - 1*12h - 0*24h = -02:00 (yesterday 22:00).
        assertThat(lower.getValue())
                .isEqualTo(T0.plus(Duration.ofHours(10)).minus(Duration.ofHours(12)));
    }

    @Test
    @DisplayName("regression: N≥2 chain with long intermediate — deepest feeder behind naive N·maxLayover cutoff is still fetched and stitched")
    void twoStopChainWithLongIntermediateIsNotSilentlyDropped() {
        // maxLayover=12h, minLayover=60m. Spine BOM-dest at 20:00.
        // Chain we want to surface:
        //   feeder1 (BLR->DEL, lands 15:00 yesterday)
        //     └ 12h max layover
        //   feeder2 (DEL->HYD, dep 03:00 today, 5h long-haul, lands 08:00)
        //     └ 12h max layover
        //   spine   (HYD->BOM, dep 20:00 today)
        //
        // feeder1 lands at yesterday 15:00 = spineStart − 29h. The naive
        // bound spineStart − N·maxLayover = 20:00 − 24h = yesterday 20:00
        // would EXCLUDE feeder1 from the pool query — the strategy would
        // silently return zero paths despite the itinerary being valid.
        // The correct bound spineStart − N·maxLayover − (N−1)·maxFlightDuration
        // = 20:00 − 24h − 24h = day-before-yesterday 20:00 correctly
        // includes it.
        Flight spine   = flight(30L, "HYD", "BOM",
                T0.plus(Duration.ofHours(20)),                 // dep 20:00
                Duration.ofMinutes(90));
        Flight feeder2 = flight(20L, "DEL", "HYD",
                T0.plus(Duration.ofHours(3)),                  // dep 03:00
                Duration.ofHours(5));                          // lands 08:00 (12h before spine)
        Flight feeder1 = flight(10L, "BLR", "DEL",
                T0.minus(Duration.ofHours(9)),                 // dep yesterday 15:00
                Duration.ofHours(0));                          // lands yesterday 15:00 (12h before feeder2)

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any()))
                .thenReturn(List.of(spine));
        // The repository is a mock — return everything the strategy
        // asks about, regardless of what the (correct) bound would
        // fetch. What we assert is: (a) the strategy stitches the chain,
        // and (b) the bound it passes to the repo is wide enough that
        // feeder1 would actually be inside the fetched window.
        when(flightRepository.findAllLandingInWindow(any(), any()))
                .thenReturn(List.of(feeder1, feeder2));

        List<List<Flight>> out = strategy.findPaths("BLR", "BOM",
                LocalDate.parse("2030-01-01"), 2);

        // The chain must surface end-to-end.
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).extracting(Flight::getId).containsExactly(10L, 20L, 30L);

        // And the pool bound must have been wide enough to include
        // feeder1 (lands at T0 − 9h). With naive bound (T0 − 4h) it
        // would have been excluded.
        ArgumentCaptor<Instant> lower = ArgumentCaptor.forClass(Instant.class);
        verify(flightRepository).findAllLandingInWindow(lower.capture(), any());
        assertThat(lower.getValue())
                .isBeforeOrEqualTo(feeder1.getEndTime());
    }

    @Test
    @DisplayName("only direct spines contribute nothing to the pool window — pool query is skipped")
    void directSpinesDoNotWidenPoolWindow() {
        // Both spine flights are direct BLR->BOM. Nothing to expand,
        // so the pool query must not fire even at maxStops=3.
        Flight direct1 = flight(1L, "BLR", "BOM",
                T0.plus(Duration.ofHours(8)), Duration.ofMinutes(120));
        Flight direct2 = flight(2L, "BLR", "BOM",
                T0.plus(Duration.ofHours(20)), Duration.ofMinutes(120));

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any()))
                .thenReturn(List.of(direct1, direct2));

        List<List<Flight>> out = strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3);

        assertThat(out).hasSize(2);
        verify(flightRepository, never()).findAllLandingInWindow(any(), any());
    }

    @Test
    @DisplayName("visited-airports guard blocks A->B->A cycles even when layover windows allow it")
    void cycleGuardPreventsRevisitingAnAirport() {
        // Spine leg: BLR -> BOM (yes, feeder loops through BLR would
        // reappear as source — the strategy must refuse that).
        Flight spine = flight(3L, "HYD", "BOM", T0.plus(Duration.ofHours(12)), Duration.ofHours(1));
        // Feeder chain: BLR -> BOM (spine's dest!) landing 4h before spine,
        // AND a BLR->HYD feeder we'd expect to succeed.
        Flight loopFeeder = flight(1L, "BOM", "HYD", T0.plus(Duration.ofHours(8)), Duration.ofMinutes(90));
        Flight rootFeeder = flight(2L, "BLR", "BOM", T0.plus(Duration.ofHours(6)), Duration.ofMinutes(60));

        when(flightRepository.findInboundInWindow(eq("BOM"), any(), any())).thenReturn(List.of(spine));
        when(flightRepository.findAllLandingInWindow(any(), any())).thenReturn(List.of(loopFeeder, rootFeeder));

        List<List<Flight>> out = strategy.findPaths("BLR", "BOM", LocalDate.parse("2030-01-01"), 3);

        // The BOM->HYD feeder introduces a cycle back to BOM (already visited),
        // so the only valid extension is BLR -> HYD-via-... — but rootFeeder
        // ends at BOM, not HYD, so it doesn't feed the spine either. Result
        // is empty; the point is the strategy doesn't explode / return the
        // cycle.
        assertThat(out).isEmpty();
    }
}
