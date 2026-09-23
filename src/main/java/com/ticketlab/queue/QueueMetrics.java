package com.ticketlab.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class QueueMetrics {

    private final MeterRegistry registry;

    /**
     * Current queue depth, published as a gauge.
     *
     * A gauge reads its value whenever Prometheus scrapes. Backing it with a
     * plain number the admitter refreshes every tick means a scrape never has
     * to call Redis - the scrape itself would otherwise add load to the thing
     * being measured.
     */
    private final AtomicLong waiting = new AtomicLong();

    public QueueMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("queue.waiting", waiting, AtomicLong::get)
                .description("사람이 줄에 몇 명 서 있는지")
                .register(registry);
    }

    /**
     * How long an admitted user actually waited in line.
     *
     * Measured on the server from the enqueue timestamp, not by the client.
     * A client polling every 500ms cannot tell 10ms from 510ms, and that error
     * would be larger than the differences this experiment is comparing.
     */
    public void recordWait(long waitedMs) {
        Timer.builder("queue.wait")
                .description("대기열에서 기다린 시간")
                .register(registry)
                .record(waitedMs, TimeUnit.MILLISECONDS);
    }

    /** 입장시킨 인원 누계. 실제 입장률이 설정값과 같은지 확인하는 데 쓴다. */
    public void recordAdmitted(int count) {
        Counter.builder("queue.admitted")
                .description("입장 허가를 받은 인원")
                .register(registry)
                .increment(count);
    }

    public void setWaiting(long count) {
        waiting.set(count);
    }
}
