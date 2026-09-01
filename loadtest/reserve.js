// 좌석 선점 부하 테스트.
//
// 질문: 좌석 SEAT_COUNT 개를 VUS 명이 동시에 노릴 때 무슨 일이 벌어지는가.
// 경합 강도 = VUS / SEAT_COUNT.  50 VU / 20석 = 2.5,  50 VU / 1석 = 50.
//
// 실행:
//   k6 run --env VUS=10 --env DURATION=10s loadtest/reserve.js

import { check } from "k6";
import http from "k6/http";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const SEAT_COUNT = Number(__ENV.SEAT_COUNT || 20);

export const options = {
  vus: Number(__ENV.VUS || 50),
  duration: __ENV.DURATION || "30s",
};

 /*
 | 테스트 시작 전에 딱 한 번 실행된다. 리턴값이 모든 VU에게 복사되어
 | default(data) 의 인자로 들어간다.
 |
 | 로그인은 반드시 여기서. default() 안에서 로그인하면 BCrypt 50ms가
 | 좌석 선점 3ms를 덮어버려서 측정값의 94%가 로그인 비용이 된다.
*/
export function setup() {
  const body = JSON.stringify({ email: "k6@test.com", password: "password123" });
  const headers = { "Content-Type": "application/json" };

  http.post(`${BASE}/api/auth/signup`, body, { headers });
  const login  = http.post(`${BASE}/api/auth/login`, body, { headers });
  const token = JSON.parse(login.body).accessToken;

  const events = JSON.parse(http.get(`${BASE}/api/events`).body);
  const eventId = events[0].id;
  const seatResponse = http.get(`${BASE}/api/events/${eventId}/seats`);
  const seatIds = JSON.parse(seatResponse.body).map(seat => seat.id).slice(0, SEAT_COUNT);
  return { token, seatIds }
}

export default function (data) {
  const seatId = data.seatIds[__ITER % data.seatIds.length];
  const body = JSON.stringify({ seatId });
  const headers = {
    "Content-Type": "application/json",
    "Authorization": `Bearer ${data.token}`,
  };

  const response = http.post(`${BASE}/api/reservations`, body, { headers });
  check(response, {
    "201 선점 성공": (r) => r.status === 201,
    "409 이미 선점됨": (r) => r.status === 409,
    "정상 응답 (201 또는 409)": (r) => r.status === 201 || r.status === 409,
  });
}
