package com.ticketlab.event;

import java.time.Instant;

public record EventResponse(Long id, String title, String venue, Instant startsAt) {
    
}
