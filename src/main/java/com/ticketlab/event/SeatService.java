package com.ticketlab.event;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class SeatService {

    private final SeatRepository seatRepository;

    public SeatService(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }
    
    public List<SeatResponse> getSeats(Long eventId) {
        List<Seat> seats = seatRepository.findByEventIdOrderBySeatNoAsc(eventId);
        return seats.stream()
                        .map(seat -> new SeatResponse(seat.getId(), seat.getSeatNo(), seat.getGrade(), seat.getPrice(), seat.getStatus()))
                        .toList();
    }
}
