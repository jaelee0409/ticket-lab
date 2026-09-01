package com.ticketlab.event;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class EventService {

    private final EventRepository eventRepository;
    
    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<EventResponse> getEvents() {
        return eventRepository.findAll().stream()
                .map(event -> new EventResponse(event.getId(), event.getTitle(), event.getVenue(), event.getStartsAt()))
                .toList();
    }
}
