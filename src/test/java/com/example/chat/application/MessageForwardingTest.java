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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MessageForwardingTest {

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("fwd_alice", "fwd_alice@test.com", "pass123", "Alice Fwd"), null);
        bob = authService.register(new RegisterRequest("fwd_bob", "fwd_bob@test.com", "pass123", "Bob Fwd"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    @Test
    @DisplayName("forward copies content into a conversation the sender belongs to")
    void forwardToOwnConversation() {
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Forward lane", null, null, List.of(bob.id())));
        PersistentMessageResponse source = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Quote of the day");

        PersistentMessageResponse copy = messageService.forwardMessage(group.id(), alice.id(), alice.username(), source.id(), null);

        assertThat(copy.id()).isNotEqualTo(source.id());
        assertThat(copy.conversationId()).isEqualTo(group.id());
        assertThat(copy.content()).isEqualTo("Quote of the day");
        assertThat(copy.senderUsername()).isEqualTo("fwd_alice");
        assertThat(copy.forwarded()).isTrue();

        List<PersistentMessageResponse> history = messageService.getMessages(group.id(), bob.id(), 0, 10);
        assertThat(history).extracting(PersistentMessageResponse::forwarded).containsExactly(true);
    }

    @Test
    @DisplayName("forwarding requires membership in both conversations")
    void membershipEnforcedOnBothSides() {
        UserProfileResponse carol = authService.register(new RegisterRequest("fwd_carol", "fwd_carol@test.com", "pass123", "Carol"), null);
        PersistentMessageResponse source = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Private note");

        assertThatThrownBy(() -> messageService.forwardMessage(direct.id(), carol.id(), carol.username(), source.id(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);

        ConversationSummaryResponse carolGroup = conversationService.createGroup(carol.id(),
            new CreateGroupRequest("Carol only", null, null, List.of()));
        assertThatThrownBy(() -> messageService.forwardMessage(carolGroup.id(), alice.id(), alice.username(), source.id(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("authorization runs before the same-conversation hint so message locations do not leak")
    void nonMemberCannotProbeOwnConversation() {
        UserProfileResponse carol = authService.register(new RegisterRequest("fwd_carol3", "fwd_carol3@test.com", "pass123", "Carol"), null);
        ConversationSummaryResponse aliceGroup = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Alice only", null, null, List.of()));
        PersistentMessageResponse inside = messageService.sendMessage(aliceGroup.id(), alice.id(), alice.username(), "Hidden");

        assertThatThrownBy(() -> messageService.forwardMessage(aliceGroup.id(), carol.id(), carol.username(), inside.id(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("deleted, missing or same-conversation forwards are refused")
    void refusedSources() {
        PersistentMessageResponse doomed = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Doomed forward");
        messageService.deleteMessage(doomed.id(), alice.id());

        assertThatThrownBy(() -> messageService.forwardMessage(direct.id(), bob.id(), bob.username(), doomed.id(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);

        assertThatThrownBy(() -> messageService.forwardMessage(direct.id(), alice.id(), alice.username(), UUID.randomUUID(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.MESSAGE_NOT_FOUND);

        PersistentMessageResponse live = messageService.sendMessage(direct.id(), bob.id(), bob.username(), "Already here");
        assertThatThrownBy(() -> messageService.forwardMessage(direct.id(), alice.id(), alice.username(), live.id(), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
    }

    @Test
    @DisplayName("retrying a forward with the same client id returns the first copy")
    void idempotentForward() {
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Retry lane", null, null, List.of(bob.id())));
        PersistentMessageResponse source = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Forward once");

        PersistentMessageResponse first = messageService.forwardMessage(group.id(), alice.id(), alice.username(), source.id(), "fwd-1");
        PersistentMessageResponse retry = messageService.forwardMessage(group.id(), alice.id(), alice.username(), source.id(), "fwd-1");

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(messageService.getMessages(group.id(), bob.id(), 0, 10)).hasSize(1);
    }
}
