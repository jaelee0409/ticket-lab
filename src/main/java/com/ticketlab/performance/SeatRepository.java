package com.ticketlab.performance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByPerformanceIdOrderBySeatNoAsc(Long performanceId);

    long countByPerformanceIdAndStatus(Long performanceId, SeatStatus status);
}
