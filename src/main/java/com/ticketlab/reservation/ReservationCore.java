package com.ticketlab.reservation;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.event.Seat;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.event.SeatStatus;
import com.ticketlab.reservation.lock.LockMetrics;
import com.ticketlab.reservation.lock.OptimisticRetryException;
import com.ticketlab.user.User;
import com.ticketlab.user.UserRepository;

@Component
public class ReservationCore {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final Duration holdDuration;
    private final LockMetrics metrics;

    private static final Logger log = LoggerFactory.getLogger(ReservationCore.class);

    public ReservationCore(ReservationRepository reservationRepository,
                            SeatRepository seatRepository,
                            UserRepository userRepository,
                            @Value("${ticketlab.reservation.hold-duration}") Duration holdDuration,
                            LockMetrics metrics) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.holdDuration = holdDuration;
        this.metrics = metrics;
    }

    @Transactional
    public ReservationResponse hold(Long userId, Long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.SEAT_NOT_FOUND));
        return holdSeat(userId, seat);
    }

    @Transactional
    public ReservationResponse holdWithLock(Long userId, Long seatId) {
        var waitSample = metrics.start();
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.SEAT_NOT_FOUND));
        metrics.recordWait("pessimistic", waitSample);
        return holdSeat(userId, seat);
    }

    private ReservationResponse holdSeat(Long userId, Seat seat) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.USER_NOT_FOUND));

        Seat seatToHold = seatRepository.findById(seat.getId())
                .orElseThrow(() -> new TicketLabException(ErrorCode.SEAT_NOT_FOUND));

        log.info("좌석 선점 요청. seatId={} status={}", seatToHold.getId(), seat.getStatus());

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        seat.hold();
        Reservation reservation = reservationRepository.save(new Reservation(user, seat, Instant.now().plus(holdDuration)));
        log.info("좌석 선점 완료. seatId={} reservationId={}", seatToHold.getId(), reservation.getId());
        
        return new ReservationResponse(reservation.getId(), seatToHold.getId(), seatToHold.getSeatNo(), reservation.getStatus(), reservation.getExpiresAt());
    }

    @Transactional
    public ReservationResponse holdOptimistic(Long userId, Long seatId) {
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.SEAT_NOT_FOUND));

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        int updated = seatRepository.compareAndSetStatus(seatId, seat.getVersion(), SeatStatus.HELD);
        if (updated == 0) {
            throw new OptimisticRetryException();
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.USER_NOT_FOUND));

        Reservation reservation = reservationRepository.save(new Reservation(user, seat, Instant.now().plus(holdDuration)));
        log.info("좌석 선점 완료. seatId={} reservationId={}", seat.getId(), reservation.getId());
        return new ReservationResponse(reservation.getId(), seat.getId(), seat.getSeatNo(), reservation.getStatus(), reservation.getExpiresAt());
    }
}
