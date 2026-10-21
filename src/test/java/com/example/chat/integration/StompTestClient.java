package com.example.chat.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.WebSocketHttpHeaders;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A real STOMP client for integration tests: an actual WebSocket connection to
 * a running server, exactly like a browser would open.
 *
 * <p>Every subscription gets its own queue, so a test can assert both what was
 * received on a destination and - by waiting for silence - what was
 * <em>not</em> received there. That is what makes "a private message never
 * reaches the public topic" a checkable claim.</p>
 */
public final class StompTestClient implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final WebSocketStompClient stompClient;
    private final StompSession session;
    private final Map<String, BlockingQueue<Object>> inboxes = new LinkedHashMap<>();

    private StompTestClient(WebSocketStompClient stompClient, StompSession session) {
        this.stompClient = stompClient;
        this.session = session;
    }

    public static StompTestClient connect(int port, ObjectMapper objectMapper) throws Exception {
        return connect(port, objectMapper, null);
    }

    /**
     * Connects to {@code /ws}. When {@code sessionCookie} (e.g.
     * {@code "JSESSIONID=..."}) is supplied it is sent with the handshake, so
     * the server resolves the real logged-in user for this connection - the
     * same thing a browser does automatically.
     */
    public static StompTestClient connect(int port, ObjectMapper objectMapper, String sessionCookie) throws Exception {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(converter);
        stompClient.setInboundMessageSizeLimit(1024 * 1024);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        if (sessionCookie != null && !sessionCookie.isBlank()) {
            headers.add("Cookie", sessionCookie);
        }

        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() { })
                .get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        return new StompTestClient(stompClient, session);
    }

    public void subscribe(String destination, Class<?> payloadType) {
        BlockingQueue<Object> inbox = new LinkedBlockingQueue<>();
        inboxes.put(destination, inbox);

        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add(payload);
            }
        });
    }

    public void send(String destination, Object payload) {
        session.send(destination, payload);
    }

    /**
     * STOMP carries no acknowledgement for SUBSCRIBE, and the server dispatches
     * inbound frames on a thread pool, so a frame sent straight after a
     * subscription can overtake it. Real clients hit the same window; here we
     * simply let the subscription land before sending anything.
     */
    public static void settle() throws InterruptedException {
        Thread.sleep(250);
    }

    /** Next message on a destination, or {@code null} if none arrives in time. */
    public <T> T poll(String destination, Class<T> type, Duration timeout) throws InterruptedException {
        Object payload = inbox(destination).poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return payload == null ? null : type.cast(payload);
    }

    /**
     * Everything currently queued on a destination, followed by the quiet
     * period. Used to assert that something was <em>not</em> delivered.
     */
    public <T> List<T> drain(String destination, Class<T> type, Duration quietPeriod) throws InterruptedException {
        BlockingQueue<Object> inbox = inbox(destination);
        List<T> collected = new ArrayList<>();

        long waitMillis = quietPeriod.toMillis();
        Object next;
        while ((next = inbox.poll(waitMillis, TimeUnit.MILLISECONDS)) != null) {
            collected.add(type.cast(next));
            waitMillis = 100;   // after the first hit, silence should be quick
        }
        return collected;
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    @Override
    public void close() {
        try {
            if (session.isConnected()) {
                session.disconnect();
            }
        } catch (RuntimeException ignored) {
            // The connection may already be gone; nothing to clean up.
        }
        stompClient.stop();
    }

    private BlockingQueue<Object> inbox(String destination) {
        BlockingQueue<Object> inbox = inboxes.get(destination);
        if (inbox == null) {
            throw new IllegalStateException("Not subscribed to " + destination);
        }
        return inbox;
    }
}
