package com.ticketlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.ticketlab.event.Event;
import com.ticketlab.event.EventRepository;
import com.ticketlab.event.Seat;
import com.ticketlab.event.SeatGrade;
import com.ticketlab.event.SeatRepository;
import com.ticketlab.event.SeatStatus;
import com.ticketlab.reservation.Reservation;
import com.ticketlab.reservation.ReservationRepository;
import com.ticketlab.reservation.ReservationStatus;
import com.ticketlab.reservation.ReservationSweeper;
import com.ticketlab.user.User;
import com.ticketlab.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReservationSweeper sweeper;

    private Long seatId;
    private String token;
    private String testEmail;

    @BeforeEach
    void setUp() throws Exception {
        testEmail = "resv-" + UUID.randomUUID() + "@test.com";

        Event event = eventRepository.save(
                new Event("테스트 공연", "테스트홀", Instant.now().plus(30, ChronoUnit.DAYS)));
        seatId = seatRepository.save(new Seat(event, "A-01", SeatGrade.R, 120_000)).getId();
        token = loginAs(testEmail);
    }

    @Test
    @DisplayName("좌석을 선점하면 201과 예약 정보를 돌려준다")
    void holdReturnsReservation() throws Exception {
        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":" + seatId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
    }

    @Test
    @DisplayName("이미 선점된 좌석을 다시 선점하면 409와 SEAT_002를 돌려준다")
    void holdRejectsTakenSeat() throws Exception {
        holdSeat(token);

        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":" + seatId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_002"))
                .andExpect(jsonPath("$.message").value("이미 선점되었거나 판매된 좌석입니다."));
    }

    @Test
    @DisplayName("없는 좌석을 선점하면 404와 SEAT_001을 돌려준다")
    void holdRejectsUnknownSeat() throws Exception {
        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SEAT_001"))
                .andExpect(jsonPath("$.message").value("좌석을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("토큰 없이 선점을 시도하면 401과 AUTH_003을 돌려준다")
    void holdRequiresToken() throws Exception {
        mockMvc.perform(post("/api/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_003"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    @DisplayName("예약을 확정하면 좌석이 판매 완료로 바뀐다")
    void confirmMarksSeatSold() throws Exception {
        Long reservationId = holdSeat(token);

        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
        .isEqualTo(SeatStatus.SOLD);
    }

    @Test
    @DisplayName("다른 사람의 예약은 확정할 수 없다")
    void confirmRejectsOtherUser() throws Exception {
        Long reservationId = holdSeat(token);
        String otherToken = loginAs("other-" + UUID.randomUUID() + "@test.com");

        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESV_002"))
                .andExpect(jsonPath("$.message").value("본인의 예약이 아닙니다."));
    }

    @Test
    @DisplayName("이미 확정된 예약은 다시 확정할 수 없다")
    void confirmRejectsTwice() throws Exception {
        Long reservationId = holdSeat(token);

        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/reservations/" + reservationId + "/confirm")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESV_003"))
                .andExpect(jsonPath("$.message").value("확정할 수 있는 상태가 아닙니다."));
    }

    @Test
    @DisplayName("기한이 지난 선점은 스케줄러가 해제한다")
    void sweeperReleasesExpiredHolds() {
        Seat seat = seatRepository.findById(seatId).orElseThrow();
        seat.hold();
        seatRepository.save(seat);

        User user = userRepository.findByEmail(testEmail).orElseThrow();
        Reservation reservation = reservationRepository.save(
                new Reservation(user, seat, Instant.now().minusSeconds(60)));
        
        sweeper.releaseExpired();

        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus())
            .isEqualTo(SeatStatus.AVAILABLE);
        assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.EXPIRED);
    }

    private String loginAs(String email) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", email, "password", "password123"));
        
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated());
        
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andReturn();
        
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asString();
    }

    private Long holdSeat(String withToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + withToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("seatId", seatId))))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();
    }
}
