package com.ticketlab.reservation.lock;

import com.ticketlab.reservation.ReservationResponse;

public interface ReservationLockStrategy {
    ReservationResponse hold(Long userId, Long seatId);
}
