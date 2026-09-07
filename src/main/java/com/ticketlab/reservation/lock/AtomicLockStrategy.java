package com.ticketlab.reservation.lock;

import org.springframework.stereotype.Component;

import com.ticketlab.reservation.ReservationCore;
import com.ticketlab.reservation.ReservationResponse;

/**
 * The simplest correct answer: one conditional UPDATE.
 *
 * No version column, no lock to wait on, no retry loop, no external service.
 * Worth measuring precisely because it is the baseline the other three have to
 * justify themselves against.
 */
@Component("atomic")
public class AtomicLockStrategy implements ReservationLockStrategy {

    private final ReservationCore core;

    public AtomicLockStrategy(ReservationCore core) {
        this.core = core;
    }

    @Override
    public ReservationResponse hold(Long userId, Long seatId) {
        return core.holdAtomic(userId, seatId);
    }
}
