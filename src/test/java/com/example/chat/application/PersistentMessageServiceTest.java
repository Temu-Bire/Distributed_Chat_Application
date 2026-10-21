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
        assertThat(messages).extracting(PersistentMessageResponse::id).contains(msg.id());
        assertThat(messages.stream().filter(m -> m.id().equals(msg.id())).findFirst().get().deleted()).isTrue();
        assertThat(messages.stream().filter(m -> m.id().equals(msg.id())).findFirst().get().content()).isEqualTo("This message was deleted.");
    }

    @Test
    @DisplayName("reply references target message and carries preview")
    void replyToMessage() {
        PersistentMessageResponse original = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Question?");
        PersistentMessageResponse reply = messageService.sendMessage(
            directConv.id(), bob.id(), bob.username(), "Here is my answer", original.id(), null);

        assertThat(reply.replyToId()).isEqualTo(original.id());
        assertThat(reply.replyToContent()).isEqualTo("Question?");

        List<PersistentMessageResponse> page = messageService.getMessages(directConv.id(), alice.id(), 0, 10);
        assertThat(page.get(1).replyToId()).isEqualTo(original.id());
    }

    @Test
    @DisplayName("cannot reply to a message from another conversation")
    void replyAcrossConversationsRejected() {
        UserProfileResponse charlie = authService.register(new RegisterRequest("msg_charlie2", "msg_charlie2@test.com", "pass123", "Charlie"), null);
        ConversationSummaryResponse otherConv = conversationService.getOrCreateDirect(bob.id(), charlie.id());
        PersistentMessageResponse foreign = messageService.sendMessage(otherConv.id(), bob.id(), bob.username(), "Elsewhere");

        assertThatThrownBy(() -> messageService.sendMessage(
            directConv.id(), alice.id(), alice.username(), "sneaky reply", foreign.id(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
    }

    @Test
    @DisplayName("retry with same client message id returns original without duplicating")
    void idempotentSend() {
        String clientId = "retry-1";
        PersistentMessageResponse first = messageService.sendMessage(
            directConv.id(), alice.id(), alice.username(), "Only once", null, clientId);
        PersistentMessageResponse retry = messageService.sendMessage(
            directConv.id(), alice.id(), alice.username(), "Only once", null, clientId);

        assertThat(retry.id()).isEqualTo(first.id());
        List<PersistentMessageResponse> page = messageService.getMessages(directConv.id(), alice.id(), 0, 10);
        assertThat(page).hasSize(1);
    }

    @Test
    @DisplayName("reaction toggles on then off, aggregated per emoji")
    void toggleReaction() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "React to me");

        PersistentMessageResponse liked = messageService.toggleReaction(msg.id(), alice.id(), "👍");
        assertThat(liked.reactions()).containsExactly(new com.example.chat.dto.message.ReactionSummaryDto("👍", 1));

        messageService.toggleReaction(msg.id(), bob.id(), "👍");
        PersistentMessageResponse both = messageService.toggleReaction(msg.id(), alice.id(), "❤️");
        assertThat(both.reactions()).extracting("emoji", "count")
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("👍", 2L),
                org.assertj.core.groups.Tuple.tuple("❤️", 1L));

        PersistentMessageResponse removed = messageService.toggleReaction(msg.id(), alice.id(), "👍");
        assertThat(removed.reactions()).extracting("emoji", "count")
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("👍", 1L),
                org.assertj.core.groups.Tuple.tuple("❤️", 1L));
    }

    @Test
    @DisplayName("non-member cannot react; blank or oversized emoji rejected")
    void reactionGuards() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Guards");
        UserProfileResponse charlie = authService.register(new RegisterRequest("msg_charlie3", "msg_charlie3@test.com", "pass123", "Charlie"), null);

        assertThatThrownBy(() -> messageService.toggleReaction(msg.id(), charlie.id(), "👍"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);

        assertThatThrownBy(() -> messageService.toggleReaction(msg.id(), alice.id(), "  "))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_REACTION);
    }

    @Test
    @DisplayName("deleted messages cannot be reacted to")
    void cannotReactToDeletedMessage() {
        PersistentMessageResponse msg = messageService.sendMessage(directConv.id(), alice.id(), alice.username(), "Doomed");
        messageService.deleteMessage(msg.id(), alice.id());

        assertThatThrownBy(() -> messageService.toggleReaction(msg.id(), bob.id(), "👍"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
    }
}
