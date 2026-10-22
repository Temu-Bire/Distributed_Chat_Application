package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
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

/**
 * The two ways a chat can be deleted that are not the two ways it already could
 * be: cleared for one member alone, and destroyed for everyone.
 */
@SpringBootTest
@Transactional
class ChatClearAndDeleteTest {

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("del_alice", "del_alice@test.com", "pass123", "Alice Del"), null);
        bob = authService.register(new RegisterRequest("del_bob", "del_bob@test.com", "pass123", "Bob Del"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "first");
        messageService.sendMessage(direct.id(), bob.id(), bob.username(), "second");
    }

    @Test
    @DisplayName("clearing a chat for me wipes my view and leaves my peer's copy whole")
    void clearAffectsOnlyTheCaller() {
        conversationService.clearConversationForUser(direct.id(), alice.id());

        assertThat(messageService.getMessages(direct.id(), alice.id(), 0, 50)).isEmpty();
        assertThat(messageService.getMessages(direct.id(), bob.id(), 0, 50))
            .extracting(PersistentMessageResponse::content)
            .containsExactly("first", "second");
    }

    @Test
    @DisplayName("a cleared chat leaves the list and comes back when something is written after the cutoff")
    void clearedChatLeavesListUntilNewActivity() {
        conversationService.clearConversationForUser(direct.id(), alice.id());
        assertThat(conversationService.getUserConversations(alice.id())).isEmpty();
        assertThat(conversationService.getUserConversations(bob.id())).hasSize(1);

        messageService.sendMessage(direct.id(), bob.id(), bob.username(), "third");

        List<ConversationSummaryResponse> back = conversationService.getUserConversations(alice.id());
        assertThat(back).hasSize(1);
        assertThat(back.get(0).lastMessageText()).isEqualTo("third");
        assertThat(back.get(0).unreadCount()).isEqualTo(1);
        assertThat(messageService.getMessages(direct.id(), alice.id(), 0, 50))
            .extracting(PersistentMessageResponse::content)
            .containsExactly("third");
    }

    @Test
    @DisplayName("search, pins and unread counts are all cut at the same boundary")
    void everyReadPathHonoursTheCutoff() {
        PersistentMessageResponse pinned = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "pin me");
        messageService.setPinned(pinned.id(), alice.id(), true);

        conversationService.clearConversationForUser(direct.id(), alice.id());

        assertThat(messageService.searchMessages(direct.id(), alice.id(), "pin", 10)).isEmpty();
        assertThat(messageService.getPinnedMessages(direct.id(), alice.id())).isEmpty();
        // Clearing for me marks nothing read for anybody else: bob still owes
        // alice one unread, exactly as much as before she cleared.
        assertThat(conversationService.getUserConversations(bob.id()))
            .singleElement()
            .satisfies(conv -> assertThat(conv.unreadCount()).isEqualTo(1));

        messageService.sendMessage(direct.id(), bob.id(), bob.username(), "pin me too");
        assertThat(messageService.searchMessages(direct.id(), alice.id(), "pin", 10))
            .extracting(PersistentMessageResponse::content)
            .containsExactly("pin me too");
        assertThat(messageService.getPinnedMessages(direct.id(), alice.id())).isEmpty();
    }

    @Test
    @DisplayName("clearing needs membership, and the conversation itself survives it")
    void clearRequiresMembership() {
        UserProfileResponse carol = authService.register(new RegisterRequest("del_carol", "del_carol@test.com", "pass123", "Carol"), null);

        assertThatThrownBy(() -> conversationService.clearConversationForUser(direct.id(), carol.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);

        conversationService.clearConversationForUser(direct.id(), alice.id());
        assertThat(conversationService.getConversationDetails(direct.id(), bob.id()).lastMessageText()).isEqualTo("second");
    }

    @Test
    @DisplayName("either participant can delete a direct chat for both, and it removes every message")
    void eitherParticipantDeletesDirectChat() {
        conversationService.deleteConversation(direct.id(), bob.id());

        assertThat(conversationService.getUserConversations(alice.id())).isEmpty();
        assertThat(conversationService.getUserConversations(bob.id())).isEmpty();
        assertThatThrownBy(() -> messageService.getMessages(direct.id(), alice.id(), 0, 50))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("a group is deleted for everyone by its owner only")
    void groupDeletionIsOwnerOnly() {
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Delete lane", null, null, List.of(bob.id()), false));
        messageService.sendMessage(group.id(), bob.id(), bob.username(), "inside the group");

        assertThatThrownBy(() -> conversationService.deleteConversation(group.id(), bob.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
        assertThat(messageService.getMessages(group.id(), bob.id(), 0, 50)).hasSize(1);

        conversationService.deleteConversation(group.id(), alice.id());
        assertThat(conversationService.getUserConversations(bob.id()))
            .extracting(ConversationSummaryResponse::id)
            .doesNotContain(group.id());
    }

    @Test
    @DisplayName("a stranger cannot delete a chat they are not in")
    void strangerCannotDelete() {
        UserProfileResponse carol = authService.register(new RegisterRequest("del_carol2", "del_carol2@test.com", "pass123", "Carol Two"), null);

        assertThatThrownBy(() -> conversationService.deleteConversation(direct.id(), carol.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
        assertThat(conversationService.getUserConversations(alice.id())).hasSize(1);
    }
}
