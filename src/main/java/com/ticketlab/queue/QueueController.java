package com.ticketlab.queue;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class QueueController {
    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/enter")
    public QueueStatusResponse enter(@AuthenticationPrincipal Long userId) {
        return queueService.enter(userId);
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@AuthenticationPrincipal Long userId) {
        return queueService.status(userId);
    }
    
    
}
