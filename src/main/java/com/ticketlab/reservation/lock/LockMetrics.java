package com.ticketlab.reservation.lock;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;

@Component
public class LockMetrics {

    private final io.micrometer.core.instrument.MeterRegistry registry;

    public LockMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    /** 락을 얻기까지 기다린 시간. */
    public void recordWait(String strategy, Timer.Sample sample) {
        sample.stop(Timer.builder("reservation.lock.wait")
                .tag("strategy", strategy)
                .register(registry));
    }

    /** hold 한 번에 필요했던 시도 횟수. 1이면 첫 시도에 성공. */
    public void recordAttempts(String strategy, int attempts) {
        DistributionSummary.builder("reservation.attempts")
                .tag("strategy", strategy)
                .register(registry)
                .record(attempts);
    }

    /** 전체 소요 시간과 결과. */
    public void recordOutcome(String strategy, String outcome, Timer.Sample sample) {
        sample.stop(Timer.builder("reservation.hold")
                .tag("strategy", strategy)
                .tag("outcome", outcome)
                .register(registry));
    }
}
