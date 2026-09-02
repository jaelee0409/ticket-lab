package com.ticketlab.reservation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.reservation.lock.ReservationLockStrategy;
import com.ticketlab.user.UserRepository;

@Service
public class ReservationService {

    private final Map<String, ReservationLockStrategy> strategies;
    private final String strategyName;

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final Duration holdDuration;

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public ReservationService(ReservationRepository reservationRepository,
            SeatRepository seatRepository,
            UserRepository userRepository,
            @Value("${ticketlab.reservation.hold-duration}") Duration holdDuration,
            Map<String, ReservationLockStrategy> strategies,
            @Value("${ticketlab.lock.strategy}") String strategyName){

        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.holdDuration = holdDuration;
        this.strategies = strategies;
        this.strategyName = strategyName;

        if (!strategies.containsKey(strategyName)) {
            throw new IllegalArgumentException(
                    "알 수 없는 락 전략: " + strategyName + " (가능한 값: " + strategies.keySet() + ")");
        }
        log.info("락 전략: {}", strategyName);
    }

    public ReservationResponse hold(Long userId, Long seatId) {
        return strategies.get(strategyName).hold(userId, seatId);
    }

    @Transactional
    public ReservationResponse confirm(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.RESERVATION_NOT_FOUND));
        
        if (!reservation.getUser().getId().equals(userId)) {
            throw new TicketLabException(ErrorCode.RESERVATION_FORBIDDEN);
        }

        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new TicketLabException(ErrorCode.RESERVATION_NOT_PENDING);
        }

        if (reservation.getExpiresAt().isBefore(Instant.now())) {
            throw new TicketLabException(ErrorCode.RESERVATION_EXPIRED);
        }

        reservation.confirm();
        reservation.getSeat().sell();
        log.info("예약 확정. reservationId={} seatId={}", reservationId, reservation.getSeat().getId());
        return new ReservationResponse(reservation.getId(), reservation.getSeat().getId(), reservation.getSeat().getSeatNo(), reservation.getStatus(), reservation.getExpiresAt());
    }
}
