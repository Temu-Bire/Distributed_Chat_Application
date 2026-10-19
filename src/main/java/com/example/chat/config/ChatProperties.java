package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Externalised settings for the chat transport.
 *
 * <p>Bound from the {@code chat.*} keys in {@code application.yml}. Defaults
 * declared here keep the application runnable with an empty configuration
 * file, and every value can be overridden at runtime without recompiling.</p>
 *
 * @param allowedOrigins          browser origins allowed to open the {@code /ws}
 *                                handshake; never a wildcard in production
 * @param inbound                 thread pool for messages arriving from clients
 * @param outbound                thread pool for messages being written back
 * @param sendTimeLimitMs         how long one outbound send may block before the
 *                                session is considered too slow and is closed
 * @param sendBufferSizeLimitBytes per-session outbound buffer ceiling
 * @param maxInboundFrameBytes    transport-level cap on a single inbound frame
 */
@ConfigurationProperties(prefix = "chat")
public record ChatProperties(
        @DefaultValue({"http://localhost:8080", "http://127.0.0.1:8080"}) List<String> allowedOrigins,
        @DefaultValue Channel inbound,
        @DefaultValue Channel outbound,
        @DefaultValue("10000") int sendTimeLimitMs,
        @DefaultValue("524288") int sendBufferSizeLimitBytes,
        @DefaultValue("65536") int maxInboundFrameBytes) {

    /**
     * Sizing for one STOMP channel executor.
     *
     * <p>Both the inbound and the outbound channel use a <em>bounded</em> pool
     * with a bounded queue. The queue is the important part: it absorbs short
     * bursts, and once it is full the configured rejection policy applies
     * instead of the JVM spawning threads without limit.</p>
     */
    public record Channel(
            @DefaultValue("4") int corePoolSize,
            @DefaultValue("16") int maxPoolSize,
            @DefaultValue("500") int queueCapacity) {
    }
}
