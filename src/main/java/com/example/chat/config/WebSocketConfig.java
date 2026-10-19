package com.example.chat.config;

import com.example.chat.application.MessageRouter;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * STOMP-over-WebSocket wiring.
 *
 * <h3>Decisions</h3>
 * <ul>
 *   <li><b>Native WebSocket, no SockJS.</b> Every target browser supports
 *       WebSocket, and dropping the fallback removes a protocol layer and a
 *       client dependency.</li>
 *   <li><b>In-process simple broker</b> for {@code /topic} and {@code /queue}.
 *       {@code /user/**} subscriptions are translated by Spring into
 *       {@code /queue/...-user<sessionId>}, so {@code /queue} must be a broker
 *       prefix for private messages and errors to be deliverable.</li>
 *   <li><b>Bounded executors</b> for both channels, with
 *       {@link ThreadPoolExecutor.CallerRunsPolicy} so overload applies
 *       back-pressure instead of dropping messages or spawning threads
 *       without limit.</li>
 *   <li><b>Heartbeats</b> so a connection that died without a close frame is
 *       noticed and cleaned up.</li>
 * </ul>
 *
 * <p>Concurrency detail — and what is <em>not</em> claimed: these pools make
 * message <em>processing</em> concurrent within this one process. They do not
 * make the application distributed; see {@code docs/architecture.md}.</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Public broadcast topics. */
    private static final String TOPIC_PREFIX = "/topic";

    /** Point-to-point destinations, including Spring's translated user queues. */
    private static final String QUEUE_PREFIX = "/queue";

    private static final String ENDPOINT = "/ws";
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private final ChatProperties properties;

    public WebSocketConfig(ChatProperties properties) {
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT)
                // Restricted explicitly: never expose the endpoint to all origins.
                .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .setHandshakeHandler(new AnonymousHandshakeHandler());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(TOPIC_PREFIX, QUEUE_PREFIX)
                .setHeartbeatValue(new long[]{HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS})
                .setTaskScheduler(heartbeatScheduler());

        // Client sends go to /app/**, replies are published by MessageRouter.
        registry.setApplicationDestinationPrefixes(MessageRouter.APP_PREFIX);

        // Enables convertAndSendToUser + the /user/queue/** subscriptions.
        registry.setUserDestinationPrefix(MessageRouter.USER_PREFIX);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // A client that cannot keep up with this buffer, within this time, is
        // disconnected rather than allowed to stall the outbound channel.
        registration.setSendTimeLimit(properties.sendTimeLimitMs());
        registration.setSendBufferSizeLimit(properties.sendBufferSizeLimitBytes());
        registration.setMessageSizeLimit(properties.maxInboundFrameBytes());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(boundedExecutor(properties.inbound(), "ws-inbound-"));
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(boundedExecutor(properties.outbound(), "ws-outbound-"));
    }

    /**
     * Builds a bounded pool.
     *
     * <p>Semantics worth noting for the {@code maxPoolSize}/{@code queueCapacity}
     * pair: the pool only grows past {@code corePoolSize} once the queue is
     * full. Because the queue is generous, the executor normally stays at core
     * size and absorbs bursts in the queue — which is what we want, since these
     * tasks are short and I/O-bound.</p>
     */
    private ThreadPoolTaskExecutor boundedExecutor(ChatProperties.Channel channel, String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(channel.corePoolSize());
        executor.setMaxPoolSize(channel.maxPoolSize());
        executor.setQueueCapacity(channel.queueCapacity());
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** Single daemon thread that drives STOMP heartbeats. */
    private TaskScheduler heartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        return scheduler;
    }
}
