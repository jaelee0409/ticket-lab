package com.ticketlab.event;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdOrderBySeatNoAsc(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);
}
