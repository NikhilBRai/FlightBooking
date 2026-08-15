package com.flightbooking.service.pricing;

import com.flightbooking.api.dto.PriceBreakdownEntry;
import com.flightbooking.domain.entity.Flight;
import com.flightbooking.domain.entity.FlightModel;
import com.flightbooking.repository.FlightSeatRepository;
import com.flightbooking.repository.FlightSeatRepository.FlightSeatCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the (count → derive available → quote) composition and,
 * critically, the batched path's <b>one COUNT query for all inputs</b>
 * guarantee — the whole reason this class exists over inline loops in
 * the callers.
 */
@ExtendWith(MockitoExtension.class)
class FlightPricingServiceTest {

    @Mock FlightSeatRepository flightSeatRepository;
    @Mock PricingService pricingService;

    @InjectMocks FlightPricingService svc;

    private static Flight flight(long id, int totalSeats) {
        FlightModel model = FlightModel.builder().id(1L).totalSeats(totalSeats).make("test").build();
        return Flight.builder().id(id).flightModel(model).cost(new BigDecimal("1000")).build();
    }

    private static FlightSeatCount row(long flightId, long count) {
        return new FlightSeatCount() {
            @Override public Long getFlightId() { return flightId; }
            @Override public Long getSeatCount() { return count; }
        };
    }

    private static PriceQuote quote(String label, BigDecimal price) {
        return new PriceQuote(price, List.of(new PriceBreakdownEntry(label, price, "n")));
    }

    // ---- Single-flight ------------------------------------------------

    @Test
    void quoteFor_derivesAvailableAsTotalMinusBooked() {
        Flight f = flight(42L, 10);
        when(flightSeatRepository.countByFlight_Id(42L)).thenReturn(3L);
        when(pricingService.quote(eq(f), anyLong(), anyInt())).thenReturn(quote("q", new BigDecimal("500")));

        svc.quoteFor(f);

        ArgumentCaptor<Long> avail = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> total = ArgumentCaptor.forClass(Integer.class);
        verify(pricingService).quote(eq(f), avail.capture(), total.capture());
        assertThat(avail.getValue()).isEqualTo(7L);
        assertThat(total.getValue()).isEqualTo(10);
    }

    @Test
    void quoteFor_returnsWhateverPricingServiceReturned() {
        Flight f = flight(1L, 10);
        PriceQuote expected = quote("x", new BigDecimal("321"));
        when(flightSeatRepository.countByFlight_Id(1L)).thenReturn(0L);
        when(pricingService.quote(eq(f), anyLong(), anyInt())).thenReturn(expected);

        assertThat(svc.quoteFor(f)).isSameAs(expected);
    }

    // ---- Batched ------------------------------------------------------

    @Test
    void quoteForAll_singleCountQueryRegardlessOfInputSize() {
        Flight a = flight(1L, 10);
        Flight b = flight(2L, 10);
        Flight c = flight(3L, 10);
        when(flightSeatRepository.countBookedByFlightIds(any()))
                .thenReturn(List.of(row(1L, 5), row(2L, 8)));
        when(pricingService.quote(any(), anyLong(), anyInt())).thenReturn(quote("q", new BigDecimal("1")));

        svc.quoteForAll(List.of(a, b, c));

        verify(flightSeatRepository, times(1)).countBookedByFlightIds(any());
        verify(flightSeatRepository, never()).countByFlight_Id(anyLong());
    }

    @Test
    void quoteForAll_missingRowsMeanZeroBooked() {
        Flight a = flight(1L, 10);
        Flight b = flight(2L, 20);
        when(flightSeatRepository.countBookedByFlightIds(any()))
                .thenReturn(List.of(row(1L, 5)));
        when(pricingService.quote(any(), anyLong(), anyInt())).thenReturn(quote("q", new BigDecimal("1")));

        svc.quoteForAll(List.of(a, b));

        // Flight 1: 10 - 5 = 5 available. Flight 2 has no row => 20 - 0 = 20.
        ArgumentCaptor<Long> avail = ArgumentCaptor.forClass(Long.class);
        verify(pricingService, times(2)).quote(any(), avail.capture(), anyInt());
        assertThat(avail.getAllValues()).containsExactlyInAnyOrder(5L, 20L);
    }

    @Test
    void quoteForAll_duplicateFlightsInInputAreDedupedAndPricedOnce() {
        Flight a = flight(7L, 10);
        when(flightSeatRepository.countBookedByFlightIds(any()))
                .thenReturn(List.of(row(7L, 4)));
        when(pricingService.quote(any(), anyLong(), anyInt())).thenReturn(quote("q", new BigDecimal("1")));

        Map<Long, PriceQuote> out = svc.quoteForAll(List.of(a, a, a));

        assertThat(out).hasSize(1).containsKey(7L);
        verify(pricingService, times(1)).quote(eq(a), anyLong(), anyInt());
    }

    @Test
    void quoteForAll_returnsMapKeyedByFlightId() {
        Flight a = flight(1L, 10);
        Flight b = flight(2L, 10);
        when(flightSeatRepository.countBookedByFlightIds(any())).thenReturn(List.of());
        PriceQuote qA = quote("a", new BigDecimal("10"));
        PriceQuote qB = quote("b", new BigDecimal("20"));
        when(pricingService.quote(eq(a), anyLong(), anyInt())).thenReturn(qA);
        when(pricingService.quote(eq(b), anyLong(), anyInt())).thenReturn(qB);

        Map<Long, PriceQuote> out = svc.quoteForAll(List.of(a, b));

        assertThat(out).containsEntry(1L, qA).containsEntry(2L, qB);
    }

    @Test
    void quoteForAll_returnsEmptyMapForNullOrEmptyInput() {
        assertThat(svc.quoteForAll(null)).isEmpty();
        assertThat(svc.quoteForAll(List.<Flight>of())).isEmpty();
        // Also verifies we don't fire the batched query for zero input.
        verify(flightSeatRepository, never()).countBookedByFlightIds(any(Collection.class));
    }
}
