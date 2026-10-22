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
 * Acting on several selected messages at once. The permissions are the ones a
 * single message already carried - this only proves they are still applied when
 * a batch makes them easier to wave through.
 */
@SpringBootTest
@Transactional
class BulkMessageOperationTest {

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;
    private PersistentMessageResponse aliceOne;
    private PersistentMessageResponse aliceTwo;
    private PersistentMessageResponse bobOne;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("bulk_alice", "bulk_alice@test.com", "pass123", "Alice Bulk"), null);
        bob = authService.register(new RegisterRequest("bulk_bob", "bulk_bob@test.com", "pass123", "Bob Bulk"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
        aliceOne = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "mine one");
        bobOne = messageService.sendMessage(direct.id(), bob.id(), bob.username(), "theirs");
        aliceTwo = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "mine two");
    }

    @Test
    @DisplayName("a selection of my own messages tombstones every one of them")
    void bulkDeleteOwnMessages() {
        int deleted = messageService.deleteMessagesBulk(direct.id(), alice.id(), List.of(aliceOne.id(), aliceTwo.id()));

        assertThat(deleted).isEqualTo(2);
        assertThat(messageService.getMessages(direct.id(), bob.id(), 0, 50))
            .extracting(PersistentMessageResponse::content)
            .containsExactly("This message was deleted.", "theirs", "This message was deleted.");
        assertThat(messageService.getMessages(direct.id(), bob.id(), 0, 50))
            .filteredOn(msg -> "theirs".equals(msg.content()))
            .singleElement()
            .satisfies(msg -> assertThat(msg.deleted()).isFalse());
    }

    @Test
    @DisplayName("a batch that reaches into someone else's message deletes nothing at all")
    void bulkDeleteRefusesOtherPeoplesMessages() {
        assertThatThrownBy(() -> messageService.deleteMessagesBulk(direct.id(), alice.id(),
            List.of(aliceOne.id(), bobOne.id())))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);

        assertThat(messageService.getMessages(direct.id(), bob.id(), 0, 50))
            .extracting(PersistentMessageResponse::deleted)
            .containsExactly(false, false, false);
    }

    @Test
    @DisplayName("a group moderator may bulk delete anyone's messages")
    void moderatorBulkDeletesForEveryone() {
        UserProfileResponse owner = authService.register(new RegisterRequest("bulk_owner", "bulk_owner@test.com", "pass123", "Owner"), null);
        ConversationSummaryResponse group = conversationService.createGroup(owner.id(),
            new CreateGroupRequest("Bulk lane", null, null, List.of(alice.id(), bob.id()), false));
        conversationService.setMemberRole(group.id(), owner.id(), alice.id(),
            com.example.chat.domain.entity.MemberRole.MODERATOR);

        PersistentMessageResponse bobNote = messageService.sendMessage(group.id(), bob.id(), bob.username(), "bob wrote this");

        int deleted = messageService.deleteMessagesBulk(group.id(), alice.id(), List.of(bobNote.id()));
        assertThat(deleted).isEqualTo(1);
        assertThat(messageService.getMessages(group.id(), bob.id(), 0, 50).get(0).deleted()).isTrue();
    }

    @Test
    @DisplayName("a selection cannot reach into another conversation")
    void refusesForeignConversationMessages() {
        UserProfileResponse carol = authService.register(new RegisterRequest("bulk_carol", "bulk_carol@test.com", "pass123", "Carol"), null);
        ConversationSummaryResponse other = conversationService.getOrCreateDirect(alice.id(), carol.id());
        PersistentMessageResponse elsewhere = messageService.sendMessage(other.id(), alice.id(), alice.username(), "other chat");

        assertThatThrownBy(() -> messageService.deleteMessagesBulk(direct.id(), alice.id(), List.of(elsewhere.id())))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
        assertThat(messageService.getMessages(direct.id(), alice.id(), 0, 50))
            .extracting(PersistentMessageResponse::deleted)
            .containsExactly(false, false, false);
    }

    @Test
    @DisplayName("messages already deleted are skipped rather than refused")
    void skipsAlreadyDeleted() {
        messageService.deleteMessage(aliceTwo.id(), alice.id());

        assertThat(messageService.deleteMessagesBulk(direct.id(), alice.id(), List.of(aliceOne.id(), aliceTwo.id())))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("several messages forward into another conversation as copies")
    void bulkForwardCopiesEverySelection() {
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Bulk target", null, null, List.of(bob.id()), false));

        List<PersistentMessageResponse> copies = messageService.forwardMessagesBulk(
            group.id(), alice.id(), alice.username(), List.of(aliceOne.id(), bobOne.id()), null);

        assertThat(copies).hasSize(2);
        assertThat(copies).allSatisfy(copy -> assertThat(copy.forwarded()).isTrue());
        assertThat(messageService.getMessages(group.id(), bob.id(), 0, 50))
            .extracting(PersistentMessageResponse::content)
            .containsExactly("mine one", "theirs");
    }

    @Test
    @DisplayName("a retried bulk forward replays the same copies instead of doubling them")
    void bulkForwardIsIdempotent() {
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Bulk replay", null, null, List.of(bob.id()), false));

        messageService.forwardMessagesBulk(group.id(), alice.id(), alice.username(),
            List.of(aliceOne.id(), aliceTwo.id()), "batch-42");
        List<PersistentMessageResponse> again = messageService.forwardMessagesBulk(group.id(), alice.id(), alice.username(),
            List.of(aliceOne.id(), aliceTwo.id()), "batch-42");

        assertThat(again).hasSize(2);
        assertThat(messageService.getMessages(group.id(), alice.id(), 0, 50)).hasSize(2);
    }

    @Test
    @DisplayName("forwarding a selection I am not entitled to writes nothing to the destination")
    void bulkForwardValidatesBeforeWriting() {
        UserProfileResponse carol = authService.register(new RegisterRequest("bulk_carol2", "bulk_carol2@test.com", "pass123", "Carol Two"), null);
        ConversationSummaryResponse carolGroup = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Carol cannot see", null, null, List.of(), false));
        ConversationSummaryResponse privateLane = conversationService.getOrCreateDirect(bob.id(), carol.id());
        PersistentMessageResponse notMine = messageService.sendMessage(privateLane.id(), carol.id(), carol.username(), "secret");

        assertThatThrownBy(() -> messageService.forwardMessagesBulk(carolGroup.id(), alice.id(), alice.username(),
            List.of(aliceOne.id(), notMine.id()), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);

        assertThat(messageService.getMessages(carolGroup.id(), alice.id(), 0, 50)).isEmpty();
    }

    @Test
    @DisplayName("only the destination's members may receive a bulk forward")
    void bulkForwardRequiresDestinationMembership() {
        UserProfileResponse carol = authService.register(new RegisterRequest("bulk_carol3", "bulk_carol3@test.com", "pass123", "Carol Three"), null);
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Members only", null, null, List.of(), false));

        assertThatThrownBy(() -> messageService.forwardMessagesBulk(group.id(), carol.id(), carol.username(),
            List.of(aliceOne.id()), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }
}
