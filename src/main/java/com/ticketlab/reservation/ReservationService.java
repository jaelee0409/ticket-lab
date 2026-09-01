package com.ticketlab.reservation;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.event.Seat;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.event.SeatStatus;
import com.ticketlab.user.User;
import com.ticketlab.user.UserRepository;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final Duration holdDuration;

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public ReservationService(ReservationRepository reservationRepository, SeatRepository seatRepository, UserRepository userRepository, @Value("${ticketlab.reservation.hold-duration}") Duration holdDuration) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.holdDuration = holdDuration;
    }

    @Transactional
    public ReservationResponse hold(Long userId, Long seatId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.USER_NOT_FOUND));

        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new TicketLabException(ErrorCode.SEAT_NOT_FOUND));

        log.info("Hold requested. seatId={} currentStatus={}", seatId, seat.getStatus());

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
        }

        seat.hold();
        Reservation reservation = reservationRepository.save(new Reservation(user, seat, Instant.now().plus(holdDuration)));
        log.info("Seat held. seatId={} reservationId={}", seatId, reservation.getId());
        
        return new ReservationResponse(reservation.getId(), seatId, seat.getSeatNo(), reservation.getStatus(), reservation.getExpiresAt());
    }

    @Transactional
    public ReservationResponse confirm(Long userId, Long reservationId) {
        // 1. find the reservation          → RESERVATION_NOT_FOUND
        // 2. owner check                   → RESERVATION_FORBIDDEN
        // 3. status must be PENDING        → RESERVATION_NOT_PENDING
        // 4. expiresAt must be in the future → RESERVATION_EXPIRED
        // 5. reservation.confirm()
        //    reservation.getSeat().sell()
        // 6. log it, then return a ReservationResponse
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
        log.info("Reservation confirmed. reservationId={} seatId={}", reservationId, reservation.getSeat().getId());
        return new ReservationResponse(reservation.getId(), reservation.getSeat().getId(), reservation.getSeat().getSeatNo(), reservation.getStatus(), reservation.getExpiresAt());
    }
}
