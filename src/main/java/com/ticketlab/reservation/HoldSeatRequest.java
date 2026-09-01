package com.ticketlab.reservation;

import jakarta.validation.constraints.NotNull;

public record HoldSeatRequest(@NotNull Long seatId) {
    
}
