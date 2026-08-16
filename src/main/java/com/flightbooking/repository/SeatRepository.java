package com.flightbooking.repository;

import com.flightbooking.domain.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByFlightModel_Id(Long flightModelId);

    /**
     * Loads a seat together with its aircraft model in a single
     * query, so callers can read {@code seat.getFlightModel()}
     * without an additional round-trip.
     */
    @Query("""
        SELECT s
          FROM Seat s
          JOIN FETCH s.flightModel
         WHERE s.id = :id
        """)
    Optional<Seat> findByIdWithFlightModel(@Param("id") Long id);

    /**
     * Returns one row per seat in the aircraft model, with a nullable
     * pointer to the corresponding {@code flight_seats} row for a
     * specific flight. A non-null pointer means the seat is booked on
     * that flight; a null pointer means it is available.
     *
     * <p>Implemented as a JPQL {@code LEFT JOIN ... ON ...} keyed on
     * the seat id and filtered to a single flight. The {@code fs.id}
     * column is projected as-is so callers can read booked/available
     * from nullness without pulling the {@code FlightSeat} row.</p>
     *
     * <p>Ordered by {@code seatNumber} so the API can render a
     * deterministic seat grid without a Java-side sort.</p>
     *
     * @param flightId       flight to check occupancy against — only
     *                       {@code flight_seats} rows tied to this
     *                       flight participate in the LEFT JOIN
     * @param flightModelId  aircraft model whose seat template is the
     *                       source of truth for the returned rows
     */
    @Query("""
        SELECT new com.flightbooking.repository.SeatOccupancyRow(s.id, s.seatNumber, fs.id)
          FROM Seat s
          LEFT JOIN FlightSeat fs
            ON fs.seat = s AND fs.flight.id = :flightId
         WHERE s.flightModel.id = :flightModelId
         ORDER BY s.seatNumber
        """)
    List<SeatOccupancyRow> findSeatOccupancy(@Param("flightId") Long flightId,
                                             @Param("flightModelId") Long flightModelId);

    /**
     * Bulk analogue of {@link #findSeatOccupancy(Long, Long)} for
     * multi-leg itinerary flows — returns the full seat layout for
     * <em>every</em> flight in {@code flightIds} in a single query
     * instead of one per flight.
     *
     * <p>Uses Hibernate's ad-hoc JOIN ... ON to pair each flight with
     * the seat template of its {@code flight_model}, then LEFT JOINs
     * {@code flight_seats} scoped to the same {@code (flight, seat)}
     * pair — so a booked seat on flight A does not incorrectly show
     * as booked on flight B when both share a model. The row's
     * {@link SeatOccupancyRow#flightId()} field is what callers
     * <em>must</em> group by, not {@link SeatOccupancyRow#seatId()}
     * alone: two flights on the same aircraft model share seat ids.
     * </p>
     *
     * <p>Rows are ordered by {@code flight_id, seat_number} so a
     * grouping pass can be a streaming {@code Collectors.groupingBy}
     * without a second sort. Empty input returns an empty list — the
     * IN clause degenerates safely.</p>
     *
     * @param flightIds every flight whose layout should be loaded;
     *                  typically the unique flight ids of a
     *                  reservation's legs
     */
    @Query("""
        SELECT new com.flightbooking.repository.SeatOccupancyRow(
                   s.id, s.seatNumber, fs.id, f.id)
          FROM Flight f
          JOIN Seat s
            ON s.flightModel.id = f.flightModel.id
          LEFT JOIN FlightSeat fs
            ON fs.seat.id = s.id AND fs.flight.id = f.id
         WHERE f.id IN :flightIds
         ORDER BY f.id, s.seatNumber
        """)
    List<SeatOccupancyRow> findSeatOccupancyForFlights(
            @Param("flightIds") Collection<Long> flightIds);
}
