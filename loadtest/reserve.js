// 좌석 선점 부하 테스트.
//
// 실제 티켓팅 화면을 흉내낸다. 사용자는 자기 화면에서 비어 보이는 좌석만
// 클릭하고, 한 번 팔린 것을 확인한 좌석은 회색 처리되어 다시 누르지 않는다.
// 경합은 "여러 사람의 화면에 같은 좌석이 아직 비어 보일 때" 생긴다.
//
// 좌석을 다 소진한 VU는 대기한다. 매진 이후에는 아무도 요청을 보내지 않으므로
// 이 시나리오의 지표는 창 안의 TPS 가 아니라 매진까지 걸린 시간이다.
//
// 실행:
//   k6 run --env VUS=500 --env DURATION=60s --env SEAT_COUNT=20 loadtest/reserve.js

import http from "k6/http";
import { check, sleep } from "k6";

http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const SEAT_COUNT = Number(__ENV.SEAT_COUNT || 20);

export const options = {
  vus: Number(__ENV.VUS || 500),
  duration: __ENV.DURATION || "60s",
};

// 테스트 시작 전에 딱 한 번 실행된다. 리턴값이 모든 VU에게 복사되어
// default(data) 의 인자로 들어간다.
//
// 로그인은 반드시 여기서. default() 안에서 로그인하면 BCrypt 50ms가
// 좌석 선점 3ms를 덮어버려서 측정값의 94%가 로그인 비용이 된다.
export function setup() {
  const body = JSON.stringify({ email: "k6@test.com", password: "password123" });
  const headers = { "Content-Type": "application/json" };

  http.post(`${BASE}/api/auth/signup`, body, { headers }); // 409면 이미 있는 것, 무시
  const login = http.post(`${BASE}/api/auth/login`, body, { headers });
  const token = JSON.parse(login.body).accessToken;

  const events = JSON.parse(http.get(`${BASE}/api/events`).body);
  const eventId = events[0].id;
  const seats = JSON.parse(http.get(`${BASE}/api/events/${eventId}/seats`).body);
  const seatIds = seats.map((seat) => seat.id).slice(0, SEAT_COUNT);

  return { token, seatIds };
}

// VU 하나가 들고 있는 좌석맵. 이 VU가 "아직 비어 있다고 믿는" 좌석들이다.
let visibleSeats = null;

export default function (data) {
  if (visibleSeats === null) {
    visibleSeats = data.seatIds.slice();
  }

  // 내 화면에 남은 좌석이 없다 = 나에게는 매진이다. 실제 사용자도 여기서 멈춘다.
  if (visibleSeats.length === 0) {
    sleep(1);
    return;
  }

  const index = Math.floor(Math.random() * visibleSeats.length);
  const seatId = visibleSeats[index];

  const response = http.post(
    `${BASE}/api/reservations`,
    JSON.stringify({ seatId }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${data.token}`,
      },
    }
  );

  check(response, {
    "201 선점 성공": (r) => r.status === 201,
    "409 이미 선점됨": (r) => r.status === 409,
    "정상 응답 (201 또는 409)": (r) => r.status === 201 || r.status === 409,
  });

  // 내가 가져갔든 남이 가져갔든 그 좌석은 이제 회색이다.
  if (response.status === 201 || response.status === 409) {
    visibleSeats.splice(index, 1);
  }
}
