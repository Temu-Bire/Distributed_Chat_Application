package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.exception.ChatException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MessageSearchTest {

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("sea_alice", "sea_alice@test.com", "pass123", "Alice Search"), null);
        bob = authService.register(new RegisterRequest("sea_bob", "sea_bob@test.com", "pass123", "Bob Search"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    @Test
    @DisplayName("search matches content case-insensitively across senders, newest first")
    void findsAnySendersMessage() {
        PersistentMessageResponse first = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "ship the QUANTUM patch");
        PersistentMessageResponse second = messageService.sendMessage(direct.id(), bob.id(), bob.username(), "quantum patch review tomorrow");
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "unrelated lunch plans");

        List<PersistentMessageResponse> found = messageService.searchMessages(direct.id(), alice.id(), "QuAnTuM", 10);
        assertThat(found).extracting(PersistentMessageResponse::id).containsExactly(second.id(), first.id());
    }

    @Test
    @DisplayName("search is scoped to the conversation")
    void scopedToConversation() {
        UserProfileResponse carol = authService.register(new RegisterRequest("sea_carol", "sea_carol@test.com", "pass123", "Carol"), null);
        ConversationSummaryResponse other = conversationService.getOrCreateDirect(bob.id(), carol.id());
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "unique token alpha");
        messageService.sendMessage(other.id(), carol.id(), carol.username(), "unique token alpha");

        List<PersistentMessageResponse> found = messageService.searchMessages(direct.id(), alice.id(), "unique token", 10);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).conversationId()).isEqualTo(direct.id());
    }

    @Test
    @DisplayName("blank query returns nothing, oversized query rejected, non-member refused")
    void guards() {
        UserProfileResponse carol = authService.register(new RegisterRequest("sea_carol2", "sea_carol2@test.com", "pass123", "Carol"), null);

        assertThat(messageService.searchMessages(direct.id(), alice.id(), "   ", 10)).isEmpty();
        assertThatThrownBy(() -> messageService.searchMessages(direct.id(), alice.id(), "A".repeat(101), 10))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
        assertThatThrownBy(() -> messageService.searchMessages(direct.id(), carol.id(), "hello", 10))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("deleted messages are excluded from search results")
    void deletedExcluded() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "temporary note to find");
        messageService.deleteMessage(msg.id(), alice.id());

        assertThat(messageService.searchMessages(direct.id(), alice.id(), "note to find", 10)).isEmpty();
    }

    @Test
    @DisplayName("LIKE metacharacters in the query match literally")
    void metacharactersAreLiteral() {
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "discount 10 dollars");

        assertThat(messageService.searchMessages(direct.id(), alice.id(), "10%", 10)).isEmpty();
        assertThat(messageService.searchMessages(direct.id(), alice.id(), "10", 10)).hasSize(1);
    }

    @Test
    @DisplayName("older pages load next-oldest messages without overlap or gaps")
    void pagesWalkBackwards() {
        for (int i = 1; i <= 5; i++) {
            messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Note " + i);
        }

        List<PersistentMessageResponse> newest = messageService.getMessages(direct.id(), alice.id(), 0, 2);
        List<PersistentMessageResponse> older = messageService.getMessages(direct.id(), alice.id(), 1, 2);
        List<PersistentMessageResponse> oldest = messageService.getMessages(direct.id(), alice.id(), 2, 2);

        assertThat(newest).extracting(PersistentMessageResponse::content).containsExactly("Note 4", "Note 5");
        assertThat(older).extracting(PersistentMessageResponse::content).containsExactly("Note 2", "Note 3");
        assertThat(oldest).extracting(PersistentMessageResponse::content).containsExactly("Note 1");
    }
}
