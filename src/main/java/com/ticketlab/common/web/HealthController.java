package com.ticketlab.common.web;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    public record HealthResponse(String status, String service, Instant timestamp) {}

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "ticket-lab", Instant.now());
    }
}
