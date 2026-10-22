package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.UserPresenceResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.dto.notification.NotificationDto;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Every frame the browser can receive has to reach subscribers that are parked
 * on a different backend instance, so each publish path is checked against the
 * cluster relay rather than only against the local broker.
 *
 * <p>The relay is a mock: the classes under test resolve it through an
 * {@code ObjectProvider} at publish time, which is exactly the seam that would
 * silently regress if a new event type were added with a local-only send.</p>
 */
@SpringBootTest
class CrossInstanceFanoutTest {

    private static final String EVENT = "event";

    @MockitoBean
    private DistributedMessageRelay relay;

    @Autowired private AuthService authService;
    @Autowired private ChatService chatService;
    @Autowired private ConversationService conversationService;
    @Autowired private PersistentMessageService messageService;
    @Autowired private TypingService typingService;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = user("fan_a");
        bob = user("fan_b");
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    @Test
    @DisplayName("a new message is relayed to the conversation topic")
    void newMessageIsRelayed() {
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "build is green");

        assertThat(relayedEvent(destinationOf(direct.id()), "NEW_MESSAGE"))
            .as("NEW_MESSAGE frame on the cluster channel")
            .isNotNull();
    }

    @Test
    @DisplayName("an edit is relayed")
    void editIsRelayed() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "typo");
        messageService.editMessage(msg.id(), alice.id(), "no typo");

        assertThat(relayedEvent(destinationOf(direct.id()), "MESSAGE_EDITED")).isNotNull();
    }

    @Test
    @DisplayName("a deletion is relayed")
    void deletionIsRelayed() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "take it back");
        messageService.deleteMessage(msg.id(), alice.id());

        assertThat(relayedEvent(destinationOf(direct.id()), "MESSAGE_DELETED")).isNotNull();
    }

    @Test
    @DisplayName("a reaction is relayed")
    void reactionIsRelayed() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "ship it");
        messageService.toggleReaction(msg.id(), bob.id(), "🎉");

        assertThat(relayedEvent(destinationOf(direct.id()), "MESSAGE_REACTED")).isNotNull();
    }

    @Test
    @DisplayName("a pin change is relayed")
    void pinIsRelayed() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "keep this");
        messageService.setPinned(msg.id(), alice.id(), true);

        assertThat(relayedEvent(destinationOf(direct.id()), "MESSAGE_PINNED")).isNotNull();
    }

    @Test
    @DisplayName("read receipts are relayed so the sender's ticks update on any node")
    void readReceiptIsRelayed() {
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "seen by bob");
        messageService.markMessagesRead(direct.id(), bob.id());

        assertThat(relayedEvent(destinationOf(direct.id()), "READ_UPDATE")).isNotNull();
    }

    @Test
    @DisplayName("typing hints are relayed even though they are ephemeral")
    void typingIsRelayed() {
        typingService.notifyTyping(direct.id(), alice.id(), alice.username(), true);

        assertThat(relayedEvent(destinationOf(direct.id()), "TYPING")).isNotNull();
    }

    @Test
    @DisplayName("a forwarded message is relayed into the destination conversation")
    void forwardIsRelayed() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "look at this");
        ConversationSummaryResponse other = conversationService.getOrCreateDirect(alice.id(), carol().id());

        messageService.forwardMessage(other.id(), alice.id(), alice.username(), msg.id(), null);

        assertThat(relayedEvent(destinationOf(other.id()), "NEW_MESSAGE")).isNotNull();
    }

    @Test
    @DisplayName("live notifications travel on the recipient's own topic")
    void notificationIsRelayed() {
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "ping for bob");

        String destination = MessageRouter.TOPIC_NOTIFICATIONS + "/" + bob.id();
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(relay, atLeastOnce()).publishCrossInstance(eq(destination), payloads.capture());
        assertThat(payloads.getAllValues())
            .anySatisfy(payload -> assertThat(payload).isInstanceOfSatisfying(
                NotificationDto.class, note -> assertThat(note.category()).isEqualTo("MESSAGE")));
    }

    @Test
    @DisplayName("a join on one node moves the online list on every node")
    void presenceIsRelayed() {
        String session = "fanout-" + suffix;
        chatService.join(session, alice.username(), alice.username());

        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(relay, atLeastOnce()).publishCrossInstance(eq(MessageRouter.TOPIC_USERS), payloads.capture());
        assertThat(payloads.getAllValues())
            .anySatisfy(payload -> assertThat(payload).isInstanceOfSatisfying(
                UserPresenceResponse.class,
                presence -> assertThat(presence.event()).isEqualTo(UserPresenceResponse.Event.JOIN)));

        chatService.leave(session);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UserProfileResponse carol() {
        return user("fan_c");
    }

    private UserProfileResponse user(String prefix) {
        String raw = prefix + "_" + suffix;
        String name = raw.length() > 20 ? raw.substring(0, 20) : raw;
        return authService.register(new RegisterRequest(name, name + "@fanout.test", "pass123", "Fanout " + prefix), null);
    }

    private static String destinationOf(UUID conversationId) {
        return MessageRouter.conversationTopic(conversationId);
    }

    /** The most recent frame of that event type this conversation pushed onto the cluster channel. */
    private Map<?, ?> relayedEvent(String destination, String event) {
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(relay, atLeastOnce()).publishCrossInstance(eq(destination), payloads.capture());
        return payloads.getAllValues().stream()
            .map(CrossInstanceFanoutTest::asMap)
            .filter(frame -> frame != null && event.equals(frame.get(EVENT)))
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private static Map<?, ?> asMap(Object payload) {
        return payload instanceof Map<?, ?> map ? map : null;
    }
}
