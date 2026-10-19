package com.example.chat.application;

import com.example.chat.domain.ChatUser;
import com.example.chat.domain.MessageType;
import com.example.chat.dto.ChatMessageResponse;
import com.example.chat.dto.UserPresenceResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.InMemoryPresenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentCaptor.forClass;

/**
 * Behaviour tests for the chat rules.
 *
 * <p>The delivery side is a Mockito mock, so these tests assert <em>routing
 * decisions</em>: which destination a message was handed to, and - just as
 * importantly - which destination it was never handed to. A real
 * {@link InMemoryPresenceStore} is used because presence is stateful and part
 * of what is under test here.</p>
 */
class ChatServiceTest {

    private MessageRouter router;
    private PresenceService presence;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        router = mock(MessageRouter.class);
        presence = new PresenceService(new InMemoryPresenceStore());
        chatService = new ChatService(presence, router);
    }

    private ChatUser join(String sessionId, String username) {
        return chatService.join(sessionId, "anon-" + sessionId, username);
    }

    @Nested
    @DisplayName("join")
    class Join {

        @Test
        void registersTheUserAndPublishesPresence() {
            join("s1", "alice");

            UserPresenceResponse presenceEvent = lastPresence();
            assertThat(presenceEvent.event()).isEqualTo(UserPresenceResponse.Event.JOIN);
            assertThat(presenceEvent.username()).isEqualTo("alice");
            assertThat(presenceEvent.users()).containsExactly("alice");
            assertThat(presenceEvent.timestamp()).isNotNull();
        }

        @Test
        void trimsSurroundingWhitespaceFromTheUsername() {
            ChatUser user = join("s1", "  alice  ");

            assertThat(user.username()).isEqualTo("alice");
        }

        @Test
        void rejectsABlankUsernameWithoutTouchingPresenceOrTransport() {
            assertThatThrownBy(() -> join("s1", "   "))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.INVALID_USERNAME);

            assertThat(presence.onlineCount()).isZero();
            verifyNoInteractions(router);
        }

        @Test
        void rejectsANullUsername() {
            assertThatThrownBy(() -> join("s1", null))
                    .isInstanceOf(ChatException.class);
        }

        @Test
        void rejectsAUsernameLongerThanTheLimit() {
            String tooLong = "a".repeat(ChatUser.MAX_USERNAME_LENGTH + 1);

            assertThatThrownBy(() -> join("s1", tooLong))
                    .isInstanceOf(ChatException.class)
                    .hasMessageContaining(String.valueOf(ChatUser.MAX_USERNAME_LENGTH));
        }

        @Test
        void acceptsAUsernameExactlyAtTheLimit() {
            String atLimit = "a".repeat(ChatUser.MAX_USERNAME_LENGTH);

            assertThat(join("s1", atLimit).username()).hasSize(ChatUser.MAX_USERNAME_LENGTH);
        }

        @Test
        void rejectsControlCharactersInAUsername() {
            assertThatThrownBy(() -> join("s1", "alice\nadmin"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.INVALID_USERNAME);
        }

        @Test
        void rejectsAUsernameAlreadyHeldByAnotherConnection() {
            join("s1", "alice");

            assertThatThrownBy(() -> join("s2", "alice"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.USERNAME_TAKEN);

            assertThat(presence.onlineUsernames()).containsExactly("alice");
        }

        @Test
        void rejectsADuplicateUsernameRegardlessOfCase() {
            join("s1", "Alice");

            assertThatThrownBy(() -> join("s2", "aLiCe"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.USERNAME_TAKEN);
        }

        @Test
        void allowsTheSameConnectionToRejoinUnderANewName() {
            join("s1", "alice");
            join("s1", "bob");

            assertThat(presence.onlineUsernames()).containsExactly("bob");
        }
    }

    @Nested
    @DisplayName("public messages")
    class PublicMessages {

        @Test
        void broadcastsWithAServerResolvedIdentityAndId() {
            join("s1", "alice");

            chatService.sendPublicMessage("s1", "hello everyone");

            ChatMessageResponse message = lastBroadcastMessage();
            assertThat(message.type()).isEqualTo(MessageType.CHAT);
            assertThat(message.sender()).isEqualTo("alice");
            assertThat(message.recipient()).isNull();
            assertThat(message.content()).isEqualTo("hello everyone");
            assertThat(message.messageId()).isNotBlank();
            assertThat(message.timestamp()).isNotNull();
        }

        @Test
        void rejectsAMessageFromAConnectionThatNeverJoined() {
            assertThatThrownBy(() -> chatService.sendPublicMessage("s1", "hello"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.NOT_JOINED);

            verifyNoInteractions(router);
        }

        @Test
        void rejectsABlankMessage() {
            join("s1", "alice");

            assertThatThrownBy(() -> chatService.sendPublicMessage("s1", "   "))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.INVALID_MESSAGE);
        }

        @Test
        void rejectsAnOversizedMessage() {
            join("s1", "alice");
            String tooLong = "x".repeat(com.example.chat.domain.ChatMessage.MAX_CONTENT_LENGTH + 1);

            assertThatThrownBy(() -> chatService.sendPublicMessage("s1", tooLong))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.INVALID_MESSAGE);
        }

        @Test
        void acceptsAMessageExactlyAtTheLimit() {
            join("s1", "alice");
            String atLimit = "x".repeat(com.example.chat.domain.ChatMessage.MAX_CONTENT_LENGTH);

            chatService.sendPublicMessage("s1", atLimit);

            assertThat(lastBroadcastMessage().content()).hasSize(com.example.chat.domain.ChatMessage.MAX_CONTENT_LENGTH);
        }
    }

    @Nested
    @DisplayName("private messages")
    class PrivateMessages {

        @Test
        void deliversToTheRecipientAndEchoesToTheSender() {
            join("s1", "alice");
            join("s2", "bob");

            chatService.sendPrivateMessage("s1", "bob", "hi bob");

            ChatMessageResponse message = messageSentPrivately();
            assertThat(message.type()).isEqualTo(MessageType.PRIVATE);
            assertThat(message.sender()).isEqualTo("alice");
            assertThat(message.recipient()).isEqualTo("bob");
            assertThat(message.content()).isEqualTo("hi bob");

            // Recipient first, then the sender's own copy.
            verify(router).sendPrivateMessage(userNamed("bob"), message);
            verify(router).sendPrivateMessage(userNamed("alice"), message);
        }

        @Test
        @DisplayName("never publishes a private message on the public topic")
        void neverLandsOnThePublicTopic() {
            join("s1", "alice");
            join("s2", "bob");

            chatService.sendPrivateMessage("s1", "bob", "top secret");

            verify(router, never()).broadcastMessage(any());
        }

        @Test
        void resolvesTheRecipientCaseInsensitivelyButKeepsTheirDisplayName() {
            join("s1", "alice");
            join("s2", "Bob");

            chatService.sendPrivateMessage("s1", "bOb", "hello");

            assertThat(messageSentPrivately().recipient()).isEqualTo("Bob");
        }

        @Test
        void rejectsAnUnknownRecipient() {
            join("s1", "alice");

            assertThatThrownBy(() -> chatService.sendPrivateMessage("s1", "ghost", "hi"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.RECIPIENT_NOT_FOUND);

            verify(router, never()).sendPrivateMessage(any(), any());
        }

        @Test
        void rejectsABlankRecipient() {
            join("s1", "alice");

            assertThatThrownBy(() -> chatService.sendPrivateMessage("s1", "  ", "hi"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.RECIPIENT_NOT_FOUND);
        }

        @Test
        void rejectsABlankBodyBeforeLookingUpTheRecipient() {
            join("s1", "alice");

            assertThatThrownBy(() -> chatService.sendPrivateMessage("s1", "bob", "   "))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.INVALID_MESSAGE);
        }

        @Test
        void rejectsAMessageFromAConnectionThatNeverJoined() {
            assertThatThrownBy(() -> chatService.sendPrivateMessage("s1", "bob", "hi"))
                    .isInstanceOf(ChatException.class)
                    .extracting(ex -> ((ChatException) ex).code())
                    .isEqualTo(ChatException.NOT_JOINED);
        }

        @Test
        void deliversASelfAddressedMessageExactlyOnce() {
            join("s1", "alice");

            chatService.sendPrivateMessage("s1", "alice", "note to self");

            verify(router, times(1)).sendPrivateMessage(any(), any());
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {

        @Test
        void removesTheUserAndPublishesTheSmallerList() {
            join("s1", "alice");
            join("s2", "bob");

            chatService.leave("s1");

            UserPresenceResponse presenceEvent = lastPresence();
            assertThat(presenceEvent.event()).isEqualTo(UserPresenceResponse.Event.LEAVE);
            assertThat(presenceEvent.username()).isEqualTo("alice");
            assertThat(presenceEvent.users()).containsExactly("bob");
        }

        @Test
        void freesTheUsernameForReuse() {
            join("s1", "alice");
            chatService.leave("s1");

            assertThat(join("s2", "alice").username()).isEqualTo("alice");
        }

        @Test
        void publishesNothingForAConnectionThatNeverJoined() {
            chatService.leave("s-unknown");

            verifyNoInteractions(router);
        }

        @Test
        void isIdempotentForTheSameConnection() {
            join("s1", "alice");

            chatService.leave("s1");
            chatService.leave("s1");

            // One broadcast for the join, one for the removal that actually happened.
            verify(router, times(2)).broadcastPresence(any());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The most recent presence broadcast (a test may join several users). */
    private UserPresenceResponse lastPresence() {
        var captor = forClass(UserPresenceResponse.class);
        verify(router, atLeastOnce()).broadcastPresence(captor.capture());
        return captor.getValue();
    }

    /** The most recent public broadcast. */
    private ChatMessageResponse lastBroadcastMessage() {
        var captor = forClass(ChatMessageResponse.class);
        verify(router, atLeastOnce()).broadcastMessage(captor.capture());
        return captor.getValue();
    }

    /** The copy delivered to the recipient, which is always the first delivery. */
    private ChatMessageResponse messageSentPrivately() {
        var captor = forClass(ChatMessageResponse.class);
        verify(router, atLeastOnce()).sendPrivateMessage(any(), captor.capture());
        return captor.getAllValues().get(0);
    }

    private ChatUser userNamed(String username) {
        return presence.findByUsername(username).orElseThrow();
    }
}
