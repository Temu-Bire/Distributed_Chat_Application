package com.example.chat.api;

import com.example.chat.application.PresenceService;
import com.example.chat.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Minimal HTTP adapter for liveness checks.
 *
 * <p>Exists so an operator (or the demo frontend) can confirm the instance is
 * up and see how many connections it holds. It delegates straight to
 * {@link PresenceService}; like the WebSocket controller it holds no business
 * logic.</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final PresenceService presence;

    public HealthController(PresenceService presence) {
        this.presence = presence;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", presence.onlineCount(), Instant.now());
    }
}
