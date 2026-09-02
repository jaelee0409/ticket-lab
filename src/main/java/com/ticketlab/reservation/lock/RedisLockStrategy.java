package com.ticketlab.reservation.lock;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.reservation.ReservationCore;
import com.ticketlab.reservation.ReservationResponse;

@Component("redis")
public class RedisLockStrategy implements ReservationLockStrategy {
    
    private final ReservationCore core;
    private final RedissonClient redissonClient;
    private final long waitMs;
    private final long leaseMs;

    public RedisLockStrategy(ReservationCore core, RedissonClient redissonClient, @Value("${ticketlab.lock.redis-wait-ms}") long waitMs,
            @Value("${ticketlab.lock.redis-lease-ms}") long leaseMs) {
        this.core = core;
        this.redissonClient = redissonClient;
        this.waitMs = waitMs;
        this.leaseMs = leaseMs;
    }

    @Override
    public ReservationResponse hold(Long userId, Long seatId) {
        RLock lock = redissonClient.getLock("seat:lock:" + seatId);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TicketLabException(ErrorCode.LOCK_ACQUIRE_FAILED);
        }
        if (!acquired) {
            throw new TicketLabException(ErrorCode.LOCK_ACQUIRE_FAILED);
        }
        try {
            return core.hold(userId, seatId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
