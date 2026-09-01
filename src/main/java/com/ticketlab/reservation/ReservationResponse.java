package com.ticketlab.reservation;

import java.time.Instant;

public record ReservationResponse(Long id, Long seatId, String seatNo, ReservationStatus status, Instant expiresAt) {
    
}
