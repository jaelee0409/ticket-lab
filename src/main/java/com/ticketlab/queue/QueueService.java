package com.ticketlab.queue;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ticketlab.common.error.ErrorCode;
import com.ticketlab.common.error.TicketLabException;

@Service
public class QueueService {
    
    private static final String WAITING = "queue:waiting";
    private static final String ADMITTED_PREFIX = "queue:admitted:";

    private final StringRedisTemplate redis;
    private final Duration admissionTtl;

    public QueueService(StringRedisTemplate redis, @Value("${ticketlab.queue.admission-ttl}") Duration admissionTtl) {
        this.redis = redis;
        this.admissionTtl = admissionTtl;
    }

    public QueueStatusResponse enter(Long userId) {
        redis.opsForZSet().addIfAbsent(WAITING, userId.toString(), System.currentTimeMillis());
        return status(userId);
    }

    public QueueStatusResponse status(Long userId) {
        String admittedKey = ADMITTED_PREFIX + userId;
        Boolean isAdmitted = redis.hasKey(admittedKey);
        if (Boolean.TRUE.equals(isAdmitted)) {
            return new QueueStatusResponse(0, true);
        } else {
            Long rank = redis.opsForZSet().rank(WAITING, userId.toString());
            if (rank != null) {
                return new QueueStatusResponse(rank.intValue() + 1, false);
            } else {
                throw new TicketLabException(ErrorCode.QUEUE_NOT_FOUND);

            }
        }
    }
}
