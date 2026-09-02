package com.ticketlab.reservation.lock;

import org.springframework.stereotype.Component;

import com.ticketlab.reservation.ReservationCore;
import com.ticketlab.reservation.ReservationResponse;

@Component("pessimistic")
public class PessimisticLockStrategy implements ReservationLockStrategy {

    private final ReservationCore core;

    public PessimisticLockStrategy(ReservationCore core) {
        this.core = core;
    }

    @Override
    public ReservationResponse hold(Long userId, Long seatId) {
        return core.holdWithLock(userId, seatId);
    }
}
