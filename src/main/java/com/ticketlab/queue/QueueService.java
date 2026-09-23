package com.ticketlab.queue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
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

    /**
     * Takes the next {@code count} people off the front of the line.
     *
     * ZPOPMIN removes and returns in one command, so two callers can never pull
     * the same person twice. That is the same reason the atomic lock strategy
     * uses a single conditional UPDATE instead of a read followed by a write.
     */
    public List<QueueEntry> pollNext(int count) {
        Set<ZSetOperations.TypedTuple<String>> popped = redis.opsForZSet().popMin(WAITING, count);
        if (popped == null || popped.isEmpty()) {
            return List.of();
        }

        List<QueueEntry> entries = new ArrayList<>(popped.size());
        for (ZSetOperations.TypedTuple<String> tuple : popped) {
            String userId = tuple.getValue();
            Double enqueuedAt = tuple.getScore();
            if (userId == null || enqueuedAt == null) {
                continue;
            }
            // The score is the millisecond timestamp written at ZADD time.
            entries.add(new QueueEntry(userId, enqueuedAt.longValue()));
        }
        return entries;
    }

    /** 줄 길이. 지표용 게이지가 매 틱 읽어간다. */
    public long waitingCount() {
        Long size = redis.opsForZSet().size(WAITING);
        return size == null ? 0L : size;
    }

    /** Issues the pass. The TTL is what eventually reclaims a no-show's slot. */
    public void admit(String userId) {
        redis.opsForValue().set(ADMITTED_PREFIX + userId, "1", admissionTtl);
    }
}
