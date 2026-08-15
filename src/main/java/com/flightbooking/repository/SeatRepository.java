package com.flightbooking.repository;

import com.flightbooking.domain.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
