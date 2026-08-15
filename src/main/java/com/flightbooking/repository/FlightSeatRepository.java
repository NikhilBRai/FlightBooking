package com.flightbooking.repository;

import com.flightbooking.domain.entity.FlightSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Sparse-inventory repository: every row here is a booked seat. There is no
 * concept of an "AVAILABLE" row.
 */
public interface FlightSeatRepository extends JpaRepository<FlightSeat, Long> {

    /** All booked seats on this flight (used to derive the seat map). */
    List<FlightSeat> findByFlight_Id(Long flightId);

    boolean existsByFlight_IdAndSeat_Id(Long flightId, Long seatId);

    long countByFlight_Id(Long flightId);

    @Modifying
    long deleteByFlight_IdAndSeat_Id(Long flightId, Long seatId);

    /**
     * The id of every booked seat on {@code flightId}, projected to
     * the raw id so the result carries no {@link FlightSeat} row and
     * no join to {@code seats}. Bounded by the aircraft's seat count.
     */
    @Query("""
        SELECT fs.seat.id
          FROM FlightSeat fs
         WHERE fs.flight.id = :flightId
        """)
    List<Long> findBookedSeatIdsByFlight_Id(@Param("flightId") Long flightId);

    /** Projection row for batch booked-count queries. */
    interface FlightSeatCount {
        Long getFlightId();
        Long getSeatCount();
    }

    /**
     * Batched booked-seat count so search doesn't do N+1 when the pricing
     * engine asks for per-flight demand across many results.
     */
    @Query("""
        SELECT fs.flight.id AS flightId, COUNT(fs) AS seatCount
          FROM FlightSeat fs
         WHERE fs.flight.id IN :flightIds
         GROUP BY fs.flight.id
        """)
    List<FlightSeatCount> countBookedByFlightIds(
            @Param("flightIds") Collection<Long> flightIds);
}
