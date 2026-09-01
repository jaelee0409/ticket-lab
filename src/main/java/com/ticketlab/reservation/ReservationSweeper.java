package com.ticketlab.reservation;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationSweeper {

    private final ReservationRepository reservationRepository;

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);

    public ReservationSweeper(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Scheduled(fixedDelayString = "${ticketlab.reservation.sweep-interval-ms}")
    @Transactional
    public void releaseExpired() {

        List<Reservation> reservations = reservationRepository.findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, Instant.now());
        if (reservations.isEmpty()) {
            return;
        }

        for (Reservation reservation : reservations) {
            reservation.expire();
            reservation.getSeat().release();
        }
        log.info("만료 예약 해제. count={}", reservations.size());
    }
}
