package com.example.chat.dto;

import java.time.Instant;

/**
 * Response body of {@code GET /api/health}.
 *
 * <p>Reports liveness plus the number of connected users, which is enough for
 * a load-balancer probe or a quick sanity check from the browser. A real
 * deployment would front this with Spring Boot Actuator and per-instance
 * presence aggregation (see {@code docs/architecture.md}).</p>
 */
public record HealthResponse(String status, int onlineUsers, Instant timestamp) {
}
