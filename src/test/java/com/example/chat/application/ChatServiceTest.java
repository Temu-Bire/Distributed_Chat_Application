package com.example.chat.application;

import com.example.chat.domain.ChatUser;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentCaptor.forClass;

/**
 * Behaviour tests for the presence rules.
 *
 * <p>The delivery side is a Mockito mock, so these tests assert <em>routing
 * decisions</em>: which presence events were handed to the transport. A real
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
}
