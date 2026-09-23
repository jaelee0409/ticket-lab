package com.ticketlab.queue;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lets people out of the waiting room at a fixed rate.
 *
 * This is the knob the whole step exists to sweep (goal 2): how many users per
 * second are allowed into the booking system. Admit too few and the queue never
 * drains; admit too many and everyone inside is slow.
 */
@Component
public class QueueAdmitter {

    private final QueueService queueService;
    private final QueueMetrics metrics;
    private final double admissionRate;
    private final long tickMs;

    /**
     * Carries the fractional part of a tick's allowance into the next tick.
     *
     * At 5 users/sec with a 100ms tick the allowance is 0.5 per tick, and
     * (int) 0.5 is 0 - without this, low rates would admit nobody, ever.
     *
     * No synchronization: @Scheduled never runs the same method concurrently,
     * so this field is only ever touched by one thread.
     */
    private double credit = 0;

    public QueueAdmitter(QueueService queueService,
                         QueueMetrics metrics,
                         @Value("${ticketlab.queue.admission-rate}") double admissionRate,
                         @Value("${ticketlab.queue.tick-ms}") long tickMs) {
        this.queueService = queueService;
        this.metrics = metrics;
        this.admissionRate = admissionRate;
        this.tickMs = tickMs;
    }

    /**
     * fixedRate, not fixedDelay: the admission rate must not drift with how
     * long the work takes. fixedDelay would wait tickMs *after* finishing,
     * making the real rate depend on Redis latency.
     */
    @Scheduled(fixedRateString = "${ticketlab.queue.tick-ms}")
    public void admit() {
        metrics.setWaiting(queueService.waitingCount());

        credit += admissionRate * tickMs / 1000.0;
        int batch = (int) credit;
        if (batch == 0) {
            return;
        }
        // Subtract before polling. An idle queue must not bank unused capacity:
        // if it did, a quiet minute would let a burst of thousands through the
        // instant people showed up, and the configured rate would be a lie.
        credit -= batch;

        List<QueueEntry> admitted = queueService.pollNext(batch);
        if (admitted.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (QueueEntry entry : admitted) {
            queueService.admit(entry.userId());
            metrics.recordWait(now - entry.enqueuedAtMs());
        }
        metrics.recordAdmitted(admitted.size());
    }
}
