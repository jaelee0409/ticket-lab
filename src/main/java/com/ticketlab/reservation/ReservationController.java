package com.ticketlab.reservation;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;
    
    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse hold(@AuthenticationPrincipal Long userId,
                                    @Valid @RequestBody HoldSeatRequest request) {
        return reservationService.hold(userId, request.seatId());
    }

    @PostMapping("/{reservationId}/confirm")
    public ReservationResponse confirm(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long reservationId) {
        return reservationService.confirm(userId, reservationId);
    }
}
