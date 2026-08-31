package com.ticketlab.event;

public record SeatResponse(Long id, String seatNo, SeatGrade grade, int price, SeatStatus status) {
}