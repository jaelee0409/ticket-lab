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
import com.ticketlab.queue.QueueService;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.reservation.lock.LockMetrics;
import com.ticketlab.reservation.lock.ReservationLockStrategy;
import com.ticketlab.user.UserRepository;

@Service
public class ReservationService {

    private final Map<String, ReservationLockStrategy> strategies;
    private final String strategyName;
    private final LockMetrics metrics;


    private final ReservationRepository reservationRepository;
    private final QueueService queueService;
    private final boolean queueEnabled;

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public ReservationService(ReservationRepository reservationRepository,
            SeatRepository seatRepository,
            UserRepository userRepository,
            @Value("${ticketlab.reservation.hold-duration}") Duration holdDuration,
            Map<String, ReservationLockStrategy> strategies,
            @Value("${ticketlab.lock.strategy}") String strategyName,
            LockMetrics metrics,
            QueueService queueService,
            @Value("${ticketlab.queue.enabled}") boolean queueEnabled){

        this.reservationRepository = reservationRepository;
        this.queueService = queueService;
        this.queueEnabled = queueEnabled;
        this.strategies = strategies;
        this.strategyName = strategyName;
        this.metrics = metrics;

        if (!strategies.containsKey(strategyName)) {
            throw new IllegalArgumentException(
                    "알 수 없는 락 전략: " + strategyName + " (가능한 값: " + strategies.keySet() + ")");
        }
        log.info("락 전략: {}  대기열: {}", strategyName, queueEnabled ? "켜짐" : "꺼짐");
    }

    public ReservationResponse hold(Long userId, Long seatId) {
        // 대기열을 켠 실험에서는 입장권이 없으면 좌석에 손도 못 댄다.
        //
        // 타이머를 시작하기 전에 막는다. 문 앞에서 돌려보낸 요청은 락을 잡아본
        // 적이 없으므로, 락 지표에 섞으면 전략별 수치가 오염된다.
        if (queueEnabled && !queueService.isAdmitted(String.valueOf(userId))) {
            throw new TicketLabException(ErrorCode.QUEUE_NOT_ADMITTED);
        }

        var sample = metrics.start();
        try {
            ReservationResponse response = strategies.get(strategyName).hold(userId, seatId);
            metrics.recordOutcome(strategyName, "acquired", sample);
            return response;
        } catch (TicketLabException e) {
            metrics.recordOutcome(strategyName, e.getErrorCode().getCode(), sample);
            throw e;
        }
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
