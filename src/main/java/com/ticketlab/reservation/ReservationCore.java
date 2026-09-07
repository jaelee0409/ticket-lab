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

    /**
     * Shared tail of the seat-claiming paths.
     *
     * The status check comes before the user lookup on purpose. Under a rush
     * almost every request loses, and a loser should not pay for a query whose
     * result it is about to throw away. Keeping this order identical across
     * strategies is also what makes their measurements comparable: an extra
     * query on the losing path would show up as a difference in lock cost.
     */
    private ReservationResponse holdSeat(Long userId, Seat seat) {
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.USER_NOT_FOUND));

        seat.hold();
        Reservation reservation = reservationRepository.save(
                new Reservation(user, seat, Instant.now().plus(holdDuration)));
        log.info("좌석 선점 완료. seatId={} reservationId={}", seat.getId(), reservation.getId());

        return new ReservationResponse(reservation.getId(), seat.getId(), seat.getSeatNo(),
                reservation.getStatus(), reservation.getExpiresAt());
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

    /**
     * Single-statement claim. The UPDATE runs first and its row count decides
     * the outcome, so a losing request costs exactly one database round trip -
     * no read, no lock, no retry.
     */
    @Transactional
    public ReservationResponse holdAtomic(Long userId, Long seatId) {
        int claimed = seatRepository.claimIfAvailable(
                seatId, SeatStatus.AVAILABLE, SeatStatus.HELD);
        if (claimed == 0) {
            throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        // Only the winner pays for these.
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.SEAT_NOT_FOUND));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.USER_NOT_FOUND));

        Reservation reservation = reservationRepository.save(
                new Reservation(user, seat, Instant.now().plus(holdDuration)));
        log.info("좌석 선점 완료. seatId={} reservationId={}", seatId, reservation.getId());

        return new ReservationResponse(reservation.getId(), seatId, seat.getSeatNo(),
                reservation.getStatus(), reservation.getExpiresAt());
    }
}
