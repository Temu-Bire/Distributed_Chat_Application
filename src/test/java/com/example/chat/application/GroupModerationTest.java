package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.conversation.UpdateGroupRequest;
import com.example.chat.dto.group.GroupInvitationResponse;
import com.example.chat.dto.group.GroupJoinRequestResponse;
import com.example.chat.dto.group.InviteToGroupRequest;
import com.example.chat.domain.entity.MemberRole;
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
class GroupModerationTest {

    @Autowired private ConversationService conversationService;
    @Autowired private GroupJoinRequestService joinRequestService;
    @Autowired private GroupInvitationService invitationService;
    @Autowired private PersistentMessageService messageService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private UserProfileResponse carol;
    private UserProfileResponse dave;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("mod_alice", "mod_alice@test.com", "pass123", "Alice Mod"), null);
        bob = authService.register(new RegisterRequest("mod_bob", "mod_bob@test.com", "pass123", "Bob Mod"), null);
        carol = authService.register(new RegisterRequest("mod_carol", "mod_carol@test.com", "pass123", "Carol Mod"), null);
        dave = authService.register(new RegisterRequest("mod_dave", "mod_dave@test.com", "pass123", "Dave Mod"), null);
    }

    private ConversationSummaryResponse group(boolean isPrivate) {
        return conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Gym " + isPrivate, "Squats", null, List.of(bob.id()), isPrivate));
    }

    /** Adds a user to a private group through the join-request approval path. */
    private void approveInto(UUID groupId, UUID userId) {
        joinRequestService.acceptJoinRequest(alice.id(), joinRequestService.requestToJoin(userId, groupId).id());
    }

    @Test
    @DisplayName("anyone can join a public group directly")
    void joinPublicGroup() {
        ConversationSummaryResponse gym = group(false);

        ConversationSummaryResponse joined = conversationService.joinGroup(gym.id(), carol.id());
        assertThat(joined.isPrivate()).isFalse();
        assertThat(conversationService.getConversationMembers(gym.id(), carol.id()))
            .extracting("userId").contains(carol.id());
    }

    @Test
    @DisplayName("private groups reject direct joins and require approval")
    void privateGroupNeedsApproval() {
        ConversationSummaryResponse gym = group(true);

        assertThatThrownBy(() -> conversationService.joinGroup(gym.id(), carol.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);

        GroupJoinRequestResponse request = joinRequestService.requestToJoin(carol.id(), gym.id());
        joinRequestService.acceptJoinRequest(alice.id(), request.id());

        assertThat(conversationService.getConversationMembers(gym.id(), alice.id()))
            .extracting("userId").contains(carol.id());
    }

    @Test
    @DisplayName("a moderator can be promoted and demoted only by the owner")
    void promoteAndDemote() {
        ConversationSummaryResponse gym = group(true);

        conversationService.setMemberRole(gym.id(), alice.id(), bob.id(), MemberRole.MODERATOR);
        assertThat(conversationService.getConversationMembers(gym.id(), alice.id()))
            .filteredOn(m -> m.userId().equals(bob.id()))
            .extracting("role").containsExactly(MemberRole.MODERATOR);

        assertThatThrownBy(() -> conversationService.setMemberRole(gym.id(), bob.id(), carol.id(), MemberRole.MODERATOR))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);

        conversationService.setMemberRole(gym.id(), alice.id(), bob.id(), MemberRole.MEMBER);
        assertThat(conversationService.getConversationMembers(gym.id(), alice.id()))
            .filteredOn(m -> m.userId().equals(bob.id()))
            .extracting("role").containsExactly(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("moderator can remove a plain member but not the owner")
    void moderatorRemovals() {
        ConversationSummaryResponse gym = group(true);
        conversationService.setMemberRole(gym.id(), alice.id(), bob.id(), MemberRole.MODERATOR);
        approveInto(gym.id(), carol.id());

        conversationService.removeMember(gym.id(), bob.id(), carol.id());
        assertThat(conversationService.getConversationMembers(gym.id(), alice.id()))
            .extracting("userId").doesNotContain(carol.id());

        assertThatThrownBy(() -> conversationService.removeMember(gym.id(), bob.id(), alice.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
    }

    @Test
    @DisplayName("blocked users lose membership and cannot rejoin, request or accept invitations")
    void blockPreventsAllEntryPaths() {
        ConversationSummaryResponse gym = group(false);

        conversationService.blockMember(gym.id(), alice.id(), bob.id());

        assertThat(conversationService.getConversationMembers(gym.id(), alice.id()))
            .extracting("userId").doesNotContain(bob.id());
        assertThat(conversationService.getBlockedMembers(gym.id(), alice.id()))
            .extracting("userId").containsExactly(bob.id());

        assertThatThrownBy(() -> conversationService.joinGroup(gym.id(), bob.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.USER_BLOCKED);
        assertThatThrownBy(() -> joinRequestService.requestToJoin(bob.id(), gym.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.USER_BLOCKED);

        List<GroupInvitationResponse> invitations = invitationService.inviteUsers(
            alice.id(), gym.id(), new InviteToGroupRequest(List.of(bob.id())));
        assertThat(invitations).isEmpty();
    }

    @Test
    @DisplayName("unblocking restores the ability to join")
    void unblockRestoresAccess() {
        ConversationSummaryResponse gym = group(false);
        conversationService.blockMember(gym.id(), alice.id(), bob.id());

        conversationService.unblockMember(gym.id(), alice.id(), bob.id());

        conversationService.joinGroup(gym.id(), bob.id());
        assertThat(conversationService.getConversationMembers(gym.id(), alice.id()))
            .extracting("userId").contains(bob.id());
    }

    @Test
    @DisplayName("owner cannot be blocked and admins cannot block themselves")
    void blockGuards() {
        ConversationSummaryResponse gym = group(true);
        conversationService.setMemberRole(gym.id(), alice.id(), bob.id(), MemberRole.MODERATOR);

        assertThatThrownBy(() -> conversationService.blockMember(gym.id(), bob.id(), alice.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
        assertThatThrownBy(() -> conversationService.blockMember(gym.id(), alice.id(), alice.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_COMMAND);
        assertThatThrownBy(() -> conversationService.blockMember(gym.id(), carol.id(), dave.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("moderator can delete another member's message, plain members cannot")
    void moderatorMessageDelete() {
        ConversationSummaryResponse gym = group(true);
        conversationService.setMemberRole(gym.id(), alice.id(), bob.id(), MemberRole.MODERATOR);
        approveInto(gym.id(), carol.id());

        var carolMsg = messageService.sendMessage(gym.id(), carol.id(), carol.username(), "spam");

        messageService.deleteMessage(carolMsg.id(), bob.id());
        assertThatThrownBy(() -> messageService.sendMessage(gym.id(), dave.id(), dave.username(), "nope"))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("privacy can be toggled by admins only")
    void togglePrivacy() {
        ConversationSummaryResponse gym = group(false);
        conversationService.joinGroup(gym.id(), carol.id()); // joins while public

        ConversationSummaryResponse updated = conversationService.updateGroup(gym.id(), alice.id(),
            new UpdateGroupRequest(gym.title(), gym.description(), null, true));
        assertThat(updated.isPrivate()).isTrue();

        assertThatThrownBy(() -> conversationService.updateGroup(gym.id(), carol.id(),
            new UpdateGroupRequest(gym.title(), null, null, false)))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
    }
}
