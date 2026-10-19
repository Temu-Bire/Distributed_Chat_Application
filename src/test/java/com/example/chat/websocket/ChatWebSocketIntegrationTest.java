package com.example.chat.websocket;

import com.example.chat.domain.MessageType;
import com.example.chat.dto.ChatMessageRequest;
import com.example.chat.dto.ChatMessageResponse;
import com.example.chat.dto.ErrorResponse;
import com.example.chat.dto.JoinRequest;
import com.example.chat.dto.PrivateMessageRequest;
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

    private static final String PUBLIC_TOPIC = "/topic/public";
    private static final String USERS_TOPIC = "/topic/users";
    private static final String PRIVATE_QUEUE = "/user/queue/private";
    private static final String ERROR_QUEUE = "/user/queue/errors";

    private static final String SEND_PUBLIC = "/app/chat.send";
    private static final String SEND_PRIVATE = "/app/chat.private";
    private static final String JOIN = "/app/chat.join";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUIET = Duration.ofMillis(400);

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

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
    @DisplayName("a public message reaches every connected client with the same id")
    void publicMessageReachesEveryone() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");

        StompTestClient aliceClient = joinAs(alice);
        StompTestClient bobClient = joinAs(bob);

        String body = "hello everyone " + SEQUENCE.incrementAndGet();
        aliceClient.send(SEND_PUBLIC, new ChatMessageRequest(body));

        ChatMessageResponse seenByAlice = await(aliceClient, PUBLIC_TOPIC, ChatMessageResponse.class,
                message -> body.equals(message.content()));
        ChatMessageResponse seenByBob = await(bobClient, PUBLIC_TOPIC, ChatMessageResponse.class,
                message -> body.equals(message.content()));

        assertThat(seenByAlice.type()).isEqualTo(MessageType.CHAT);
        assertThat(seenByAlice.sender()).isEqualTo(alice);
        assertThat(seenByAlice.recipient()).isNull();
        assertThat(seenByAlice.messageId()).isNotBlank();
        assertThat(seenByAlice.timestamp()).isNotNull();

        assertThat(seenByBob.sender()).isEqualTo(alice);
        assertThat(seenByBob.messageId()).isEqualTo(seenByAlice.messageId());
    }

    @Test
    @DisplayName("a sender field smuggled into the payload is ignored")
    void senderInPayloadCannotImpersonateAnotherUser() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");

        StompTestClient aliceClient = joinAs(alice);
        StompTestClient bobClient = joinAs(bob);

        // ChatMessageRequest has no sender field, so this extra property is
        // simply dropped during conversion. The assertion is that the message
        // is still attributed to the connection that actually sent it.
        String body = "impersonation attempt " + SEQUENCE.incrementAndGet();
        aliceClient.send(SEND_PUBLIC, new SpoofingMessage(body, bob));

        ChatMessageResponse delivered = await(bobClient, PUBLIC_TOPIC, ChatMessageResponse.class,
                message -> body.equals(message.content()));

        assertThat(delivered.sender())
                .as("the session identity must win over anything in the payload")
                .isEqualTo(alice);
    }

    @Test
    @DisplayName("a private message reaches only the two participants")
    void privateMessageReachesOnlyTheParticipants() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");
        String carol = uniqueName("carol");

        StompTestClient aliceClient = joinAs(alice);
        StompTestClient bobClient = joinAs(bob);
        StompTestClient carolClient = joinAs(carol);

        String secret = "secret-" + SEQUENCE.incrementAndGet();
        aliceClient.send(SEND_PRIVATE, new PrivateMessageRequest(bob, secret));

        ChatMessageResponse receivedByBob = await(bobClient, PRIVATE_QUEUE, ChatMessageResponse.class,
                message -> secret.equals(message.content()));
        assertThat(receivedByBob.type()).isEqualTo(MessageType.PRIVATE);
        assertThat(receivedByBob.sender()).isEqualTo(alice);
        assertThat(receivedByBob.recipient()).isEqualTo(bob);

        // The sender gets their own copy so the conversation reads naturally.
        ChatMessageResponse echoedToAlice = await(aliceClient, PRIVATE_QUEUE, ChatMessageResponse.class,
                message -> secret.equals(message.content()));
        assertThat(echoedToAlice.recipient()).isEqualTo(bob);

        // Carol is online but is not a participant: she must see nothing.
        assertThat(carolClient.drain(PRIVATE_QUEUE, ChatMessageResponse.class, QUIET))
                .as("an unrelated online user must not receive the private message")
                .isEmpty();
    }

    @Test
    @DisplayName("a private message is never published on the public topic")
    void privateMessageNeverAppearsOnThePublicTopic() throws Exception {
        String alice = uniqueName("alice");
        String bob = uniqueName("bob");
        String carol = uniqueName("carol");

        StompTestClient aliceClient = joinAs(alice);
        StompTestClient bobClient = joinAs(bob);
        StompTestClient carolClient = joinAs(carol);

        String secret = "classified-" + SEQUENCE.incrementAndGet();
        aliceClient.send(SEND_PRIVATE, new PrivateMessageRequest(bob, secret));

        // Make sure the delivery really happened, so the negative assertions
        // below are not passing merely because nothing was sent at all.
        await(bobClient, PRIVATE_QUEUE, ChatMessageResponse.class, message -> secret.equals(message.content()));

        for (StompTestClient client : List.of(aliceClient, bobClient, carolClient)) {
            assertThat(client.drain(PUBLIC_TOPIC, ChatMessageResponse.class, QUIET))
                    .as("private content must never reach %s", PUBLIC_TOPIC)
                    .noneMatch(message -> secret.equals(message.content()));
        }
    }

    @Test
    @DisplayName("a private message to a user who is offline is refused")
    void privateMessageToAnOfflineUserIsRefused() throws Exception {
        String alice = uniqueName("alice");
        StompTestClient aliceClient = joinAs(alice);

        aliceClient.send(SEND_PRIVATE, new PrivateMessageRequest(uniqueName("ghost"), "anyone there?"));

        ErrorResponse error = await(aliceClient, ERROR_QUEUE, ErrorResponse.class,
                response -> "RECIPIENT_NOT_FOUND".equals(response.code()));
        assertThat(error.message()).contains("not online");
        assertThat(error.timestamp()).isNotNull();
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
    @DisplayName("blank and oversized messages are refused")
    void blankAndOversizedMessagesAreRefused() throws Exception {
        String alice = uniqueName("alice");
        StompTestClient aliceClient = joinAs(alice);

        aliceClient.send(SEND_PUBLIC, new ChatMessageRequest("   "));
        ErrorResponse blank = await(aliceClient, ERROR_QUEUE, ErrorResponse.class,
                response -> "INVALID_MESSAGE".equals(response.code()));
        assertThat(blank.message()).isNotBlank();

        aliceClient.send(SEND_PUBLIC, new ChatMessageRequest("x".repeat(2001)));
        ErrorResponse oversized = await(aliceClient, ERROR_QUEUE, ErrorResponse.class,
                response -> "INVALID_MESSAGE".equals(response.code()) && response.message().contains("2000"));
        assertThat(oversized.message()).contains("2000");

        // Nothing invalid reached the public topic.
        assertThat(aliceClient.drain(PUBLIC_TOPIC, ChatMessageResponse.class, QUIET)).isEmpty();
    }

    @Test
    @DisplayName("an oversized username is refused without registering the user")
    void oversizedUsernameIsRefused() throws Exception {
        StompTestClient client = openClient();

        client.send(JOIN, new JoinRequest("u".repeat(21)));

        ErrorResponse error = await(client, ERROR_QUEUE, ErrorResponse.class,
                response -> response.code().startsWith("INVALID"));
        assertThat(error.message()).contains("20");
    }

    @Test
    @DisplayName("messages sent before joining are refused")
    void messagesBeforeJoiningAreRefused() throws Exception {
        StompTestClient stranger = openClient();

        stranger.send(SEND_PUBLIC, new ChatMessageRequest("hello"));

        ErrorResponse error = await(stranger, ERROR_QUEUE, ErrorResponse.class,
                response -> "NOT_JOINED".equals(response.code()));
        assertThat(error.message()).contains("Join");
    }

    @Test
    @DisplayName("a structurally invalid frame is reported and does not kill the connection")
    void invalidFrameIsReported() throws Exception {
        String alice = uniqueName("alice");
        StompTestClient aliceClient = joinAs(alice);

        // content is missing entirely, so Bean Validation must reject it.
        aliceClient.send(SEND_PUBLIC, new ChatMessageRequest(null));

        ErrorResponse error = await(aliceClient, ERROR_QUEUE, ErrorResponse.class,
                response -> "INVALID_MESSAGE".equals(response.code()));
        assertThat(error.message()).isNotBlank();

        // The connection is still usable afterwards.
        String body = "still alive " + SEQUENCE.incrementAndGet();
        aliceClient.send(SEND_PUBLIC, new ChatMessageRequest(body));
        assertThat(await(aliceClient, PUBLIC_TOPIC, ChatMessageResponse.class,
                message -> body.equals(message.content())).sender()).isEqualTo(alice);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A payload type with an extra field, used to attempt impersonation. */
    private record SpoofingMessage(String content, String sender) {
    }

    private StompTestClient openClient() throws Exception {
        StompTestClient client = StompTestClient.connect(port, objectMapper);
        clients.add(client);

        client.subscribe(PUBLIC_TOPIC, ChatMessageResponse.class);
        client.subscribe(USERS_TOPIC, UserPresenceResponse.class);
        client.subscribe(PRIVATE_QUEUE, ChatMessageResponse.class);
        client.subscribe(ERROR_QUEUE, ErrorResponse.class);
        StompTestClient.settle();

        assertThat(client.isConnected()).isTrue();
        return client;
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
