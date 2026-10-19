package com.example.chat.application;

import com.example.chat.domain.entity.ConversationType;
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

@SpringBootTest
@Transactional
class PersistentMessageServiceTest {

    @Autowired
    private PersistentMessageService messageService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse directConv;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("msg_alice", "msg_alice@test.com", "pass123", "Alice M"), null);
        bob = authService.register(new RegisterRequest("msg_bob", "msg_bob@test.com", "pass123", "Bob M"), null);
        directConv = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    @Test
    @DisplayName("sending message persists message and updates conversation last message")
    void sendMessageSuccess() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Hello Bob!");

        assertThat(msg.id()).isNotNull();
        assertThat(msg.content()).isEqualTo("Hello Bob!");
        assertThat(msg.senderUsername()).isEqualTo("msg_alice");
        assertThat(msg.edited()).isFalse();
        assertThat(msg.deleted()).isFalse();

        ConversationSummaryResponse updatedConv = conversationService.getConversationDetails(directConv.id(), alice.id());
        assertThat(updatedConv.lastMessageText()).isEqualTo("Hello Bob!");
        assertThat(updatedConv.lastMessageSender()).isEqualTo("msg_alice");
    }

    @Test
    @DisplayName("reject blank or oversized messages")
    void sendInvalidMessages() {
        assertThatThrownBy(() -> messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "   "))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_MESSAGE);

        String tooLong = "A".repeat(2001);
        assertThatThrownBy(() -> messageService.sendMessage(directConv.id(), alice.id(), alice.username(), tooLong))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_MESSAGE);
    }

    @Test
    @DisplayName("non-member cannot send message to conversation")
    void nonMemberCannotSendMessage() {
        UserProfileResponse charlie = authService.register(new RegisterRequest("msg_charlie", "msg_charlie@test.com", "pass123", "Charlie"), null);

        assertThatThrownBy(() -> messageService.sendMessage(directConv.id(), charlie.id(), charlie.username(), "Sneaking in"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("paginate messages returns messages in chronological order")
    void paginateMessages() {
        for (int i = 1; i <= 5; i++) {
            messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Msg " + i);
        }

        List<PersistentMessageResponse> page = messageService.getMessages(directConv.id(), alice.id(), 0, 10);
        assertThat(page).hasSize(5);
        assertThat(page.get(0).content()).isEqualTo("Msg 1");
        assertThat(page.get(4).content()).isEqualTo("Msg 5");
    }

    @Test
    @DisplayName("sender can edit their own message")
    void editMessage() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Original text");

        PersistentMessageResponse edited = messageService.editMessage(msg.id(), alice.id(), "Updated text");
        assertThat(edited.content()).isEqualTo("Updated text");
        assertThat(edited.edited()).isTrue();
        assertThat(edited.editedAt()).isNotNull();
    }

    @Test
    @DisplayName("user cannot edit another user's message")
    void editOtherUserMessageFails() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Alice msg");

        assertThatThrownBy(() -> messageService.editMessage(msg.id(), bob.id(), "Bob tries to edit"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
    }

    @Autowired
    private com.example.chat.infrastructure.repository.MessageRepository messageRepository;

    @Test
    @DisplayName("soft delete marks message deleted and excludes from recent messages")
    void softDeleteMessage() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "To be deleted");

        messageService.deleteMessage(msg.id(), alice.id());

        var messageInDb = messageRepository.findById(msg.id()).orElseThrow();
        assertThat(messageInDb.isDeleted()).isTrue();
        assertThat(messageInDb.getContent()).isEqualTo("This message was deleted.");

        List<PersistentMessageResponse> messages = messageService.getMessages(directConv.id(), alice.id(), 0, 10);
        assertThat(messages).extracting(PersistentMessageResponse::id).doesNotContain(msg.id());
    }
}
