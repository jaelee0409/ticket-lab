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

    @Modifying
    @Query("UPDATE Seat s SET s.status = :status, s.version = s.version + 1 " +
           "WHERE s.id = :id AND s.version = :version")
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("version") long version,
                            @Param("status") SeatStatus status);
}
