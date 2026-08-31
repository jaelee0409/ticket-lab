package com.ticketlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.ticketlab.payment.Payment;
import com.ticketlab.payment.PaymentRepository;
import com.ticketlab.payment.PaymentStatus;
import com.ticketlab.event.Event;
import com.ticketlab.event.Seat;
import com.ticketlab.event.SeatGrade;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.event.SeatStatus;
import com.ticketlab.reservation.Reservation;
import com.ticketlab.reservation.ReservationRepository;
import com.ticketlab.reservation.ReservationStatus;
import com.ticketlab.user.User;
import com.ticketlab.user.UserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class DomainMappingTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("공연에 속한 좌석을 저장하고 좌석번호 순으로 다시 읽는다")
    void savesSeatsAndReadsThemBackInOrder() {
        Event concert = em.persist(newEvent());
        em.persist(new Seat(concert, "A-02", SeatGrade.R, 120_000));
        em.persist(new Seat(concert, "A-01", SeatGrade.VIP, 180_000));
        em.flush();
        em.clear();

        List<Seat> seats = seatRepository.findByEventIdOrderBySeatNoAsc(concert.getId());

        assertThat(seats).extracting(Seat::getSeatNo).containsExactly("A-01", "A-02");
        assertThat(seats).allMatch(seat -> seat.getStatus() == SeatStatus.AVAILABLE);
    }

    @Test
    @DisplayName("좌석에서 공연으로 가는 연관관계는 실제로 지연 로딩된다")
    void eventIsFetchedLazilyFromSeat() {
        Event concert = em.persist(newEvent());
        Seat saved = em.persist(new Seat(concert, "B-07", SeatGrade.S, 90_000));
        em.flush();
        em.clear();

        Seat seat = seatRepository.findById(saved.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(seat.getEvent()))
                .as("공연을 건드리기 전에는 프록시 상태여야 한다")
                .isFalse();

        assertThat(seat.getEvent().getTitle()).isEqualTo("한여름 밤의 재즈");

        assertThat(Hibernate.isInitialized(seat.getEvent()))
                .as("필드를 읽는 순간 조회 쿼리가 나가고 초기화된다")
                .isTrue();
    }

    @Test
    @DisplayName("예약은 사용자와 좌석을 함께 가리킨다")
    void reservationLinksUserAndSeat() {
        User buyer = em.persist(new User("buyer@ticketlab.dev", "hashed"));
        Event concert = em.persist(newEvent());
        Seat seat = em.persist(new Seat(concert, "C-11", SeatGrade.A, 60_000));

        Reservation saved = em.persist(
                new Reservation(buyer, seat, Instant.now().plus(5, ChronoUnit.MINUTES)));
        em.flush();
        em.clear();

        Reservation found = reservationRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(found.getExpiresAt()).isNotNull();
        assertThat(found.getUser().getEmail()).isEqualTo("buyer@ticketlab.dev");
        assertThat(found.getSeat().getSeatNo()).isEqualTo("C-11");

        assertThat(reservationRepository.findBySeatId(seat.getId())).hasSize(1);
    }

    @Test
    @DisplayName("좌석 상태를 바꾸면 save 호출 없이 반영된다")
    void statusChangeIsFlushedWithoutCallingSave() {
        Event concert = em.persist(newEvent());
        Seat seat = em.persist(new Seat(concert, "D-01", SeatGrade.S, 90_000));
        em.flush();

        seat.hold();

        em.flush();
        em.clear();

        Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("열거형은 순번이 아니라 이름 문자열로 저장된다")
    void enumsAreStoredAsNames() {
        Event concert = em.persist(newEvent());
        Seat seat = em.persist(new Seat(concert, "E-09", SeatGrade.VIP, 180_000));
        em.flush();

        Object stored = em.getEntityManager()
                .createNativeQuery("SELECT status FROM seat WHERE id = :id")
                .setParameter("id", seat.getId())
                .getSingleResult();

        assertThat(stored).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("같은 멱등성 키로 결제를 두 번 저장하면 거부된다")
    void duplicateIdempotencyKeyIsRejected() {
        Reservation first = persistReservation("first@ticketlab.dev", "F-01");
        Reservation second = persistReservation("second@ticketlab.dev", "F-02");

        paymentRepository.saveAndFlush(new Payment(first, 120_000, "same-key"));

        assertThatThrownBy(
                () -> paymentRepository.saveAndFlush(new Payment(second, 120_000, "same-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("결제는 응답을 못 받은 상태를 UNKNOWN으로 남긴다")
    void paymentCanRecordAnUnknownOutcome() {
        Reservation reservation = persistReservation("timeout@ticketlab.dev", "G-01");
        Payment payment = em.persist(new Payment(reservation, 90_000, "key-timeout"));

        payment.markUnknown();
        em.flush();
        em.clear();

        Payment found = paymentRepository.findByIdempotencyKey("key-timeout").orElseThrow();

        assertThat(found.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(found.getExternalTxId())
                .as("응답을 못 받았으니 거래 번호도 없다")
                .isNull();
        assertThat(paymentRepository.findByStatus(PaymentStatus.UNKNOWN)).hasSize(1);
    }

    @Test
    @DisplayName("같은 이메일로 사용자를 두 번 만들 수 없다")
    void duplicateEmailIsRejected() {
        userRepository.saveAndFlush(new User("dup@ticketlab.dev", "hashed"));

        assertThatThrownBy(
                () -> userRepository.saveAndFlush(new User("dup@ticketlab.dev", "other")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Event newEvent() {
        return new Event(
                "한여름 밤의 재즈",
                "블루스퀘어 마스터카드홀",
                Instant.now().plus(30, ChronoUnit.DAYS));
    }

    private Reservation persistReservation(String email, String seatNo) {
        User user = em.persist(new User(email, "hashed"));
        Event concert = em.persist(newEvent());
        Seat seat = em.persist(new Seat(concert, seatNo, SeatGrade.R, 120_000));
        return em.persist(new Reservation(user, seat, Instant.now().plus(5, ChronoUnit.MINUTES)));
    }
}
