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

@SpringBootTest
@Transactional
class PinnedMessageTest {

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private UserProfileResponse carol;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("pin_alice", "pin_alice@test.com", "pass123", "Alice Pin"), null);
        bob = authService.register(new RegisterRequest("pin_bob", "pin_bob@test.com", "pass123", "Bob Pin"), null);
        carol = authService.register(new RegisterRequest("pin_carol", "pin_carol@test.com", "pass123", "Carol Pin"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    private ConversationSummaryResponse group(UserProfileResponse... members) {
        return conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Pinning crew", null, null, List.of(members).stream().map(UserProfileResponse::id).toList()));
    }

    @Test
    @DisplayName("direct message members can pin and unpin")
    void directMembersPin() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Roadmap");

        PersistentMessageResponse pinned = messageService.setPinned(msg.id(), bob.id(), true);
        assertThat(pinned.pinned()).isTrue();

        List<PersistentMessageResponse> pinnedList = messageService.getPinnedMessages(direct.id(), alice.id());
        assertThat(pinnedList).extracting(PersistentMessageResponse::id).containsExactly(msg.id());

        PersistentMessageResponse unpinned = messageService.setPinned(msg.id(), alice.id(), false);
        assertThat(unpinned.pinned()).isFalse();
        assertThat(messageService.getPinnedMessages(direct.id(), alice.id())).isEmpty();
    }

    @Test
    @DisplayName("non-member cannot pin a message")
    void nonMemberCannotPin() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Members only");

        assertThatThrownBy(() -> messageService.setPinned(msg.id(), carol.id(), true))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("group members cannot pin, moderators and owners can")
    void groupPinRequiresManager() {
        ConversationSummaryResponse gym = group(bob, carol);
        conversationService.setMemberRole(gym.id(), alice.id(), bob.id(), com.example.chat.domain.entity.MemberRole.MODERATOR);
        PersistentMessageResponse msg = messageService.sendMessage(gym.id(), carol.id(), carol.username(), "Rules");

        assertThatThrownBy(() -> messageService.setPinned(msg.id(), carol.id(), true))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);

        assertThat(messageService.setPinned(msg.id(), bob.id(), true).pinned()).isTrue();
        assertThat(messageService.setPinned(msg.id(), alice.id(), false).pinned()).isFalse();
        assertThat(messageService.setPinned(msg.id(), bob.id(), true).pinned()).isTrue();
    }

    @Test
    @DisplayName("plain member unpins only their own message")
    void groupUnpinSenderOrManager() {
        ConversationSummaryResponse gym = group(bob, carol);
        PersistentMessageResponse own = messageService.sendMessage(gym.id(), carol.id(), carol.username(), "Carol note");
        PersistentMessageResponse other = messageService.sendMessage(gym.id(), alice.id(), alice.username(), "Alice note");
        messageService.setPinned(own.id(), alice.id(), true);
        messageService.setPinned(other.id(), alice.id(), true);

        assertThat(messageService.setPinned(own.id(), carol.id(), false).pinned()).isFalse();

        assertThatThrownBy(() -> messageService.setPinned(other.id(), carol.id(), false))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
    }

    @Test
    @DisplayName("deleted message cannot be pinned")
    void deletedMessageCannotPin() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Doomed pin");
        messageService.deleteMessage(msg.id(), alice.id());

        assertThatThrownBy(() -> messageService.setPinned(msg.id(), alice.id(), true))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
    }

    @Test
    @DisplayName("unknown message id rejected")
    void unknownMessageRejected() {
        assertThatThrownBy(() -> messageService.setPinned(java.util.UUID.randomUUID(), alice.id(), true))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.MESSAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("pinned list requires membership and orders newest pin first")
    void pinnedListGuards() {
        PersistentMessageResponse first = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "First pin");
        PersistentMessageResponse second = messageService.sendMessage(direct.id(), bob.id(), bob.username(), "Second pin");
        messageService.setPinned(first.id(), alice.id(), true);
        messageService.setPinned(second.id(), alice.id(), true);

        assertThatThrownBy(() -> messageService.getPinnedMessages(direct.id(), carol.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);

        List<PersistentMessageResponse> pinned = messageService.getPinnedMessages(direct.id(), bob.id());
        assertThat(pinned).extracting(PersistentMessageResponse::id).containsExactly(second.id(), first.id());
    }
}
