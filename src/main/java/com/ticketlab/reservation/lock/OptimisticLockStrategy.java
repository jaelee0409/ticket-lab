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

    public OptimisticLockStrategy(ReservationCore core, @Value("${ticketlab.lock.optimistic-max-retries}") int maxRetries) {
        this.core = core;
        this.maxRetries = maxRetries;
    }

    @Override
    public ReservationResponse hold(Long userId, Long seatId) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return core.holdOptimistic(userId, seatId);
            } catch (OptimisticRetryException e) {
                // 다음 시도로
            }
        }
        throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE);
    }
}
