package com.ticketlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.ticketlab.common.error.TicketLabException;
import com.ticketlab.event.Event;
import com.ticketlab.event.EventRepository;
import com.ticketlab.event.Seat;
import com.ticketlab.event.SeatGrade;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.event.SeatStatus;
import com.ticketlab.reservation.ReservationRepository;
import com.ticketlab.reservation.lock.ReservationLockStrategy;
import com.ticketlab.user.User;
import com.ticketlab.user.UserRepository;

/**
 * The test this whole step exists for.
 *
 * Every strategy is exercised through the same scenario: one seat, many threads,
 * all released at the same instant. Exactly one of them may end up holding it.
 *
 * Strategies are pulled straight out of the bean map rather than going through
 * ReservationService, because the active strategy is fixed by configuration at
 * startup and one test run cannot restart the context three times.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ConcurrentHoldTest {

    private static final int THREADS = 20;

    @Autowired
    private Map<String, ReservationLockStrategy> strategies;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @ParameterizedTest(name = "{0} 전략은 좌석 하나를 한 명에게만 준다")
    @ValueSource(strings = {"pessimistic", "optimistic", "redis"})
    void onlyOneWinnerPerSeat(String strategyName) throws Exception {
        ReservationLockStrategy strategy = strategies.get(strategyName);
        assertThat(strategy).as("전략 '%s' 이 등록되어 있어야 한다", strategyName).isNotNull();

        Long seatId = freshSeat();
        List<Long> userIds = freshUsers(THREADS);

        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        // 출발 신호. 20개 스레드가 전부 여기서 멈춰 있다가 동시에 튀어나간다.
        CountDownLatch startGate = new CountDownLatch(1);
        // 결승선. 20번 세어질 때까지 메인 스레드가 기다린다.
        CountDownLatch finished = new CountDownLatch(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (Long userId : userIds) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        strategy.hold(userId, seatId);
                        acquired.incrementAndGet();
                    } catch (TicketLabException e) {
                        // 좌석을 못 잡은 것. 경합의 정상적인 결과다.
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 그 외 전부는 버그. 조용히 넘기면 테스트가 거짓말을 한다.
                        unexpected.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("모든 스레드가 60초 안에 끝나야 한다")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get()).as("예상하지 못한 예외").isZero();
        assertThat(acquired.get()).as("좌석을 얻은 스레드 수").isEqualTo(1);
        assertThat(rejected.get()).as("밀려난 스레드 수").isEqualTo(THREADS - 1);

        // 카운터는 애플리케이션이 그렇게 믿는다는 뜻일 뿐이다.
        // 실제로 그런지는 데이터베이스에 물어봐야 안다.
        assertThat(reservationRepository.findBySeatId(seatId))
                .as("좌석 %d 의 예약", seatId)
                .hasSize(1);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
    }

    /** 매 실행마다 새 공연과 좌석. @SpringBootTest 는 롤백하지 않는다. */
    private Long freshSeat() {
        Event event = eventRepository.save(
                new Event("동시성 테스트", "측정용", Instant.now().plus(30, ChronoUnit.DAYS)));
        return seatRepository.save(new Seat(event, "A-01", SeatGrade.R, 120_000)).getId();
    }

    private List<Long> freshUsers(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            User user = userRepository.save(
                    new User("rush-" + UUID.randomUUID() + "@test.com", "hashed"));
            ids.add(user.getId());
        }
        return ids;
    }
}
