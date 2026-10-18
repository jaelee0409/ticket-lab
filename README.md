# ticket-lab

[한국어](README.ko.md)

A seat-booking API built to answer one question with load tests: when many requests hit the same seat at once, as at a ticket opening, how should the server decide who gets it?

**Stack:** Java 21, Spring Boot 4, JPA, PostgreSQL 18.6, Redis 8.8 with Redisson 3.50.0, Flyway, k6, Testcontainers, Prometheus, Grafana

## Results at a glance

- **Double bookings: 5–18 per run → 0**, by moving the availability check into the UPDATE.
- **Five strategies compared from 1 to 500 seats.** Pessimistic locking was 2.3x slower than optimistic at 1 seat. The conditional UPDATE was chosen because it needs no read first, no version column, and no retry loop; at 1 seat it is slower than optimistic (63 vs 52 ms p50).
- **Redis lock at 500 seats:** at least 2.2x lower throughput, p95 156 ms vs 39–44 ms.
- **Connection pool:** the result in earlier versions of this README is withdrawn; a rerun is planned. See [section 3](#3-connection-pool-size-result-withdrawn).

## 1. Double booking → 0

**Problem.** Holding a seat took three steps: read → check status → save. With no lock and 200 concurrent users, every run double-booked 5–18 times, and one seat collected up to 10 bookings.

**Cause.** A read → check → save race: concurrent requests could each read the seat as AVAILABLE before any of them saved, and each then saved a booking.

**Fix.** The check moved into the write: `UPDATE … WHERE status = 'AVAILABLE'`. If 0 rows change, the seat is taken and the API returns 409.

```java
// SeatRepository.java
@Modifying
@Query("UPDATE Seat s SET s.status = :to WHERE s.id = :id AND s.status = :from")
int claimIfAvailable(@Param("id") Long id, @Param("from") SeatStatus from, @Param("to") SeatStatus to);

// ReservationCore.holdAtomic, inside @Transactional
int claimed = seatRepository.claimIfAvailable(seatId, SeatStatus.AVAILABLE, SeatStatus.HELD);
if (claimed == 0) {
    throw new TicketLabException(ErrorCode.SEAT_NOT_AVAILABLE); // 409 Conflict
}
```

**Result.** 0 double bookings. Across all 30 load runs (10 seat counts × 3 runs), each of the four locking strategies had 0 duplicate or excess bookings, while no lock double-booked in every run (up to 18).

**How it's tested.** `ConcurrentHoldTest` (`@SpringBootTest` with Testcontainers, so a real PostgreSQL) releases 20 threads together with a `CountDownLatch`. The test was confirmed to fail without the fix. It asserts that the database holds exactly 1 booking, not just that the app counted 1 success. All four locking strategies pass: 1 success, 19 rejected, 1 booking.

## 2. Lock strategy comparison

Five strategies run under the same conditions, swapped with one environment variable:

| Strategy | Implementation |
|---|---|
| No lock | read → check → save; the baseline that double-books |
| Pessimistic | JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` on the seat query (`SELECT … FOR UPDATE`), held until commit |
| Optimistic | reads the seat and returns 409 if it isn't AVAILABLE, then `UPDATE … WHERE version = ?`; 0 rows → retry, up to 3 times (`OPTIMISTIC_MAX_RETRIES`), and the re-read returns 409 |
| Conditional UPDATE | `UPDATE … WHERE status = 'AVAILABLE'`; 0 rows → 409. One `@Transactional` method (`holdAtomic`): the UPDATE first, then the user lookup and the reservation INSERT, so the row lock is held until commit |
| Redis lock | Redisson `RLock` per seat (`seat:lock:{id}`), which waits for the unlock through pub/sub instead of spinning; `tryLock` wait 3000 ms, lease 5000 ms; wrapped around the same transactional hold as no lock |

**p50 response time (ms), median of 3 runs per condition.** Fewer seats means more requests fighting over the same seat.

| Seats | No lock | Pessimistic | Optimistic | Conditional UPDATE | Redis lock |
|---:|---:|---:|---:|---:|---:|
| 1 | 48 | 118 | 52 | 63 | 317 |
| 5 | 65 | 82 | 63 | 75 | 173 |
| 10 | 59 | 57 | 62 | 60 | 88 |
| 500 | 28 | 29 | 28 | 28 | 57 |

No lock is in the table only as a baseline: it double-books.

![Lock strategy comparison by seat count](loadtest/results/lock-strategies.png)

*The chart's labels are in Korean: 원자적 CAS is the conditional UPDATE. Besides p50, it plots p95 and excess bookings per seat count.*

### What the numbers show

- **Pessimistic vs optimistic.** Pessimistic was 2.3x slower at 1 seat (118 vs 52 ms) and 1.3x slower at 5 seats (82 vs 63 ms; run-to-run ranges 70–90 vs 61–68 ms, no overlap). Once a seat is sold, a losing request can only fail, but pessimistic locking makes it wait in the lock queue first.
- **Conditional UPDATE vs pessimistic.** 1.9x faster at 1 seat (63 vs 118 ms). At 5 seats the 1.1x gap (75 vs 82 ms) is inside the run-to-run noise (64–77 vs 70–90 ms).
- **Conditional UPDATE vs optimistic.** The conditional UPDATE was slower at 1 seat (63 vs 52 ms), outside the noise (58–73 vs 51–57 ms); at 5 seats (75 vs 63 ms) the ranges overlap. The gap is measured. The explanation below is inferred from the code, not measured:
  - Isolation is PostgreSQL's default, READ COMMITTED. The conditional UPDATE runs first in its transaction, so the winner holds the row lock until commit. A loser that arrives meanwhile waits for that commit; PostgreSQL then re-evaluates the WHERE on the new row version, 0 rows change, and the API returns 409.
  - Optimistic's `UPDATE … WHERE version = ?` can wait the same way if it reaches the row before the winner commits. The difference is that optimistic reads first, without a lock, so in the burst most of its losers see the seat already HELD and leave without touching the lock. Every conditional-UPDATE loser goes straight to the row.
- **From 10 seats on, the four database strategies are within noise.** No lock, pessimistic, optimistic, and the conditional UPDATE are within 57–62 ms at 10 seats and 28–29 ms at 500 seats. At 10 seats their run-to-run ranges overlap (51–77 ms across the four).
- **Redis lock.** Slowest p50 at every seat count in the table: at 1 seat, 6.1x slower than the fastest correct strategy (317 ms vs optimistic's 52 ms). At 50 seats it had the best p50 (38 vs 45–48 ms) but a p95 of 161 ms vs 58–71 ms.

**Tail latency at 1 seat (ms).** A 1-seat run is one burst of about 200 requests, so p99 is roughly the 2nd-slowest request.

| Strategy | p95 | p99 |
|---|---:|---:|
| Pessimistic | 189 | 193 |
| Optimistic | 78 | 95 |
| Conditional UPDATE | 85 | 87 |
| Redis lock | 530 | 544 |

**Redis lock at 500 seats.** In the 15-second window, the Redis lock's three runs sent 34,935, 42,976, and 43,243 of the 100,004 requests (median run: 42,976 at 2,836.8 req/s, still sending at the 15-second mark). The database strategies sent all 100,004 in 11 of 12 runs; pessimistic run 1 sent 99,706. Throughput was at least 2.2x lower (2,837 vs about 6,240 req/s), and p95 was 156 ms vs 39–44 ms.

Why "at least": each VU idles in `sleep(1)` after trying every seat, and k6's req/s divides by the whole test time, idle tail included (about 15.7–16.1 s). The database strategies sent all 100,000 seat requests within the 15 seconds, so their real rate was at least 6,667 req/s, and the ~6,240 req/s figure understates them.

### Why the conditional UPDATE

- **Options:** pessimistic, optimistic, conditional UPDATE, Redis lock. No lock was only a baseline.
- **Decision:** the conditional UPDATE. One statement checks and writes atomically: no read first, no version column, no retry loop, and no exception-to-409 mapping.
- **What it costs:** at 1 seat its p50 is 63 ms vs optimistic's 52 ms. Every losing request goes straight to the row, and one that arrives before the winner commits waits for that commit (inferred, above).
- **What it matches:** p99 at 1 seat on par with optimistic (87 vs 95 ms; about 200 samples per run), 1.9x faster than pessimistic at 1 seat, and within noise of the other database strategies from 10 seats on.

## 3. Connection pool size: result withdrawn

Earlier versions of this README reported a pool-size sweep (5 to 200 connections) and concluded that the default of 10 was enough. That conclusion doesn't hold, and every pool-size claim from that section is withdrawn.

- **Why it's invalid.** The workload was finite: each virtual user tries every seat once. In the pool sweep (500 seats, conditional UPDATE), every run from pool 20 up finished all 100,004 requests inside the 15-second window, so requests per second couldn't rise past about 6,300. The flat line measured the test running out of work, not a server limit. The k6 script's own comment says to use time to sellout instead.
- **Planned rerun.** A workload that can't run out: k6's constant-arrival-rate executor, or more seats. No pool-size conclusion goes back in this README until those numbers exist.

Raw data from the withdrawn sweep: [`loadtest/results/pool.csv`](loadtest/results/pool.csv).

## Methodology

- k6 with 200 concurrent virtual users (VUs) for a fixed 15 seconds. PostgreSQL fixed at 4 cores and 4 GB.
- The database is reset for each condition, warm-up is discarded, and each result is the median of 3 runs.
- Seat counts swept: 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000. The 1000-seat runs used the same 500 seats (same 100,004 requests, same results).
- The workload is finite. `setup()` makes 4 requests (signup, login, events, seats). Then each VU tries every seat once, in random order, and idles in `sleep(1)` until the 15 seconds are up. A run is 200 × seats + 4 requests (204 at 1 seat, 100,004 at 500 seats).
- k6's req/s divides by the whole test time, idle tail included, so it understates any strategy that finishes before the 15 seconds are up.
- k6 and the server ran on the same machine, so only comparisons between conditions are claimed, not absolute numbers.
- Raw data: [`loadtest/results/contention.csv`](loadtest/results/contention.csv) (lock comparison) and [`loadtest/results/pool.csv`](loadtest/results/pool.csv) (withdrawn pool sweep).
- Also in the repo: k6 load scenarios, scripts that repeat measurements per condition, a Prometheus + Grafana dashboard, and Testcontainers integration tests.

## How to run

```bash
docker compose up -d --wait     # PostgreSQL, Redis, Prometheus, Grafana
./gradlew bootRun               # http://localhost:8080/actuator/health
```

Experiments (Windows PowerShell):

```powershell
.\gradlew.bat bootJar
.\loadtest\run-experiment.ps1 -Sweep SEAT_COUNT -Values 1,2,5,10,20,50,100,200,500 `
  -Strategies none,pessimistic,optimistic,atomic,redis -Vus 200 -Duration 15s `
  -Out "loadtest/results/contention.csv"
python loadtest/plot_strategies.py --max-seats 500
```

`-Duration 15s` matches the measured runs; the script's default is 20s. In `-Strategies`, `atomic` is the conditional UPDATE. Outside the script, the `LOCK_STRATEGY` environment variable selects the lock strategy and `DB_POOL_SIZE` sets the pool size. Other settings are in `application.yml`.

## Structure

```
src/main/java/com/ticketlab/
├── auth/             JWT authentication
├── common/           security, error handling, logging
├── event/            shows and seats
├── payment/          Payment entity, repository, and status only (no payment flow)
├── queue/            waiting queue (unfinished)
├── reservation/      seat holds
│   └── lock/         the 5 strategies
└── user/             User entity

loadtest/
├── reserve.js              k6 scenario
├── run-experiment.ps1      repeats measurements across conditions
├── plot_*.py               charts
└── results/                result CSVs and charts
```

## Design notes

- **Optimistic locking doesn't use JPA `@Version`.** `Seat` has a plain `version` field, updated by a hand-written `UPDATE … WHERE version = ?` (`compareAndSetStatus`). `@Version` applies to every write to the seat, so it would also guard the "no lock" variant, which is left unprotected on purpose for comparison.
- **`open-in-view: false`.** With it on, each request holds its database connection until the response finishes, which would make connection-pool measurements meaningless.

## AI assistance

10 of the 44 commits were written with Claude and carry a `Co-Authored-By: Claude` trailer.

---

Jae Seong Lee · jaelee0409@gmail.com · [github.com/jaelee0409](https://github.com/jaelee0409)
