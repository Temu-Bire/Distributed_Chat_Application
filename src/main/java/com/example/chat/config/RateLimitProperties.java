package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Budget for the fixed-window {@code RateLimiter}.
 *
 * <p>Counts are per minute and held in this instance's memory. That is the
 * correct scope for a single-node deployment; behind several instances each
 * node enforces its own budget, which multiplies the effective global limit by
 * the instance count. Redis-backed counting is the upgrade path noted in
 * {@code docs/architecture.md}, not a prerequisite for stopping brute-force
 * logins from one node.</p>
 *
 * @param enabled                master switch; disabled only for tests that
 *                               exercise protocol behaviour rather than limits
 * @param loginPerMinute         attempts per client IP on {@code POST /api/auth/login}
 * @param registerPerMinute      attempts per client IP on {@code POST /api/auth/register}
 * @param friendRequestPerMinute requests per user (or IP while anonymous)
 * @param messagesPerMinute      persisted messages per sender, covering both
 *                               the REST and the STOMP send paths
 */
@ConfigurationProperties(prefix = "chat.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int loginPerMinute,
        @DefaultValue("5") int registerPerMinute,
        @DefaultValue("20") int friendRequestPerMinute,
        @DefaultValue("60") int messagesPerMinute) {
}
