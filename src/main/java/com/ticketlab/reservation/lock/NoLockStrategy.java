package com.ticketlab.reservation.lock;

import org.springframework.stereotype.Component;

import com.ticketlab.reservation.ReservationCore;
import com.ticketlab.reservation.ReservationResponse;

@Component("none")
public class NoLockStrategy implements ReservationLockStrategy {

    private final ReservationCore core;

    public NoLockStrategy(ReservationCore core) {
        this.core = core;
    }

    @Override
    public ReservationResponse hold(Long userId, Long seatId) {
        return core.hold(userId, seatId);
    }
}
