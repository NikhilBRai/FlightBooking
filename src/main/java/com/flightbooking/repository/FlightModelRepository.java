package com.flightbooking.repository;

import com.flightbooking.domain.entity.FlightModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlightModelRepository extends JpaRepository<FlightModel, Long> {
}
