package com.ticketlab.reservation.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.reservation.ReservationCore;
import com.ticketlab.reservation.ReservationResponse;

@Component("optimistic")
public class OptimisticLockStrategy implements ReservationLockStrategy {
 
    private final int maxRetries;
    private final ReservationCore core;
    private final LockMetrics metrics;

    public OptimisticLockStrategy(ReservationCore core, @Value("${ticketlab.lock.optimistic-max-retries}") int maxRetries, LockMetrics metrics) {
        this.core = core;
        this.maxRetries = maxRetries;
        this.metrics = metrics;
    }

    @Override
    public ReservationResponse hold(Long userId, Long seatId) {
        int attempt = 0;
        try {
            for (attempt = 1; attempt <= maxRetries + 1; attempt++) {
                try {
                    return core.holdOptimistic(userId, seatId);
                } catch (OptimisticRetryException e) {
                    // 다음 시도로
                }
            }
            throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
        } finally {
            metrics.recordAttempts("optimistic", Math.min(attempt, maxRetries + 1));
        }
    }
}
