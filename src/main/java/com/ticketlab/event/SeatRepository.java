package com.ticketlab.event;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdOrderBySeatNoAsc(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> findByIdForUpdate(@Param("id") Long id);

    /**
     * Claims a seat in a single statement: the check and the write cannot be
     * separated, so nothing can slip between them.
     *
     * Returns 1 when this caller won the seat and 0 when it did not. A zero
     * cannot distinguish "already taken" from "no such seat" - that is the
     * price of doing it in one round trip, and on a workload that is mostly
     * rejections the round trip is what matters.
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = :to WHERE s.id = :id AND s.status = :from")
    int claimIfAvailable(@Param("id") Long id,
                         @Param("from") SeatStatus from,
                         @Param("to") SeatStatus to);

    @Modifying
    @Query("UPDATE Seat s SET s.status = :status, s.version = s.version + 1 " +
           "WHERE s.id = :id AND s.version = :version")
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("version") long version,
                            @Param("status") SeatStatus status);
}
