package com.ticketlab.event;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeatController {

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @GetMapping("/api/events/{id}/seats")
    public List<SeatResponse> getSeats(@PathVariable Long id) {
        return seatService.getSeats(id);
    }
}
