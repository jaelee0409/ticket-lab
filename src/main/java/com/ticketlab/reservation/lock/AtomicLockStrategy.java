package com.ticketlab.reservation.lock;

import org.springframework.stereotype.Component;

import com.ticketlab.reservation.ReservationCore;
import com.ticketlab.reservation.ReservationResponse;

/**
 * The simplest correct answer: one conditional UPDATE.
 *
 * No version column, no explicit lock, no retry loop, no external service.
 * The UPDATE still takes a row lock: a concurrent claim on the same seat waits
 * for the winner's commit, then re-checks the WHERE and updates 0 rows.
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
