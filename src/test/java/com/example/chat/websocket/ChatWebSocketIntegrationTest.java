package com.example.chat.websocket;

import com.example.chat.dto.ErrorResponse;
import com.example.chat.dto.JoinRequest;
import com.example.chat.dto.UserPresenceResponse;
import com.example.chat.integration.StompTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over a real WebSocket: several simultaneous STOMP clients,
 * the real broker, the real presence store.
 *
 * <p>These are behaviour tests, not "does the context start" tests. Each one
 * drives the application through its public protocol and asserts what each
 * client observed - including what it was not supposed to observe.</p>
 *
 * <p>The context is shared between methods, and so is the presence store, so
 * every test uses unique display names. That keeps the tests independent
 * without paying for a fresh context each time.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketIntegrationTest {

    private static final String USERS_TOPIC = "/topic/users";
    private static final String ERROR_QUEUE = "/user/queue/errors";

    private static final String JOIN = "/app/chat.join";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofMillis(400);

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    private final List<StompTestClient> clients = new ArrayList<>();

    @AfterEach
    void disconnectEveryClient() {
        clients.forEach(StompTestClient::close);
        clients.clear();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("joining publishes presence and the full user list to everyone")
    void joiningPublishesPresenceToEveryone() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");

        StompTestClient aliceClient = openClient();
        aliceClient.send(JOIN, new JoinRequest(alice));
        assertThat(awaitPresence(aliceClient, UserPresenceResponse.Event.JOIN, alice).users())
                .containsExactly(alice);

        StompTestClient bobClient = openClient();
        bobClient.send(JOIN, new JoinRequest(bob));
        assertThat(awaitPresence(bobClient, UserPresenceResponse.Event.JOIN, bob).users())
                .containsExactlyInAnyOrder(alice, bob);

        // The client that was already connected learns about the new arrival.
        assertThat(awaitPresence(aliceClient, UserPresenceResponse.Event.JOIN, bob).users())
                .containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    @DisplayName("a duplicate username is refused and the connection can try again")
    void duplicateUsernameIsRefused() throws Exception {
        String alice = uniqueName("alice");

        StompTestClient aliceClient = joinAs(alice);

        StompTestClient second = openClient();
        second.send(JOIN, new JoinRequest(alice.toUpperCase()));

        ErrorResponse error = await(second, ERROR_QUEUE, ErrorResponse.class,
                response -> "USERNAME_TAKEN".equals(response.code()));
        assertThat(error.message()).contains("already in use");

        // A refused join must not change anyone's picture of who is online.
        assertThat(aliceClient.drain(USERS_TOPIC, UserPresenceResponse.class, QUIET))
                .as("no presence update may be published for a refused join")
                .isEmpty();

        // The same connection can recover by choosing another name.
        String alternative = uniqueName("alice2");
        second.send(JOIN, new JoinRequest(alternative));

        UserPresenceResponse recovered = awaitPresence(second, UserPresenceResponse.Event.JOIN, alternative);
        assertThat(recovered.users()).containsExactlyInAnyOrder(alice, alternative);
    }

    @Test
    @DisplayName("disconnecting removes the user and tells the others")
    void disconnectingRemovesTheUser() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");

        StompTestClient aliceClient = joinAs(alice);
        StompTestClient bobClient = joinAs(bob);

        bobClient.close();
        clients.remove(bobClient);

        UserPresenceResponse left = awaitPresence(aliceClient, UserPresenceResponse.Event.LEAVE, bob);
        assertThat(left.users()).containsExactly(alice);
    }

    @Test
    @DisplayName("an oversized username is refused without registering the user")
    void oversizedUsernameIsRefused() throws Exception {
        StompTestClient client = openClient();

        client.send(JOIN, new JoinRequest("u".repeat(21)));

        // The DTO layer caught this, but the client still learns it was the
        // username that was wrong, not the message body.
        ErrorResponse error = await(client, ERROR_QUEUE, ErrorResponse.class,
                response -> "INVALID_USERNAME".equals(response.code()));
        assertThat(error.message()).contains("20");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Opens a real browser-like connection: the client registers a database
     * user, keeps the session cookie, and sends it with the WebSocket
     * handshake so the server sees an authenticated principal (subscription
     * guards reject anonymous subscribers on protected topics).
     */
    private StompTestClient openClient() throws Exception {
        return openClient(uniqueName("user"));
    }

    private StompTestClient openClient(String accountName) throws Exception {
        String cookie = registerAndLogin(accountName);
        StompTestClient client = StompTestClient.connect(port, objectMapper, cookie);
        clients.add(client);

        client.subscribe(USERS_TOPIC, UserPresenceResponse.class);
        client.subscribe(ERROR_QUEUE, ErrorResponse.class);
        StompTestClient.settle();

        assertThat(client.isConnected()).isTrue();
        return client;
    }

    private String registerAndLogin(String username) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        java.util.Map<String, Object> body = java.util.Map.of(
                "username", username,
                "displayName", username,
                "email", username + "@ws-test.local",
                "password", "Password123!");
        var response = restTemplate.postForEntity("/api/auth/register",
                new org.springframework.http.HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CREATED);
        String setCookie = response.getHeaders().getFirst(org.springframework.http.HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("session cookie on registration").isNotNull();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    private StompTestClient joinAs(String username) throws Exception {
        StompTestClient client = openClient();
        client.send(JOIN, new JoinRequest(username));
        awaitPresence(client, UserPresenceResponse.Event.JOIN, username);
        return client;
    }

    private UserPresenceResponse awaitPresence(StompTestClient client,
            UserPresenceResponse.Event event, String username) throws InterruptedException {
        return await(client, USERS_TOPIC, UserPresenceResponse.class,
                presence -> presence.event() == event && username.equals(presence.username()));
    }

    /** Polls a destination until a message matches, failing the test on timeout. */
    private <T> T await(StompTestClient client, String destination, Class<T> type,
            Predicate<T> matches) throws InterruptedException {

        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            T message = client.poll(destination, type, Duration.ofMillis(250));
            if (message != null && matches.test(message)) {
                return message;
            }
        }
        throw new AssertionError("Timed out waiting for a message on " + destination);
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet()
                + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000));
    }
}
