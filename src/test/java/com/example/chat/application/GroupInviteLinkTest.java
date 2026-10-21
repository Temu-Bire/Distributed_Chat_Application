package com.example.chat.application;

import com.example.chat.domain.entity.GroupInviteLinkEntity;
import com.example.chat.domain.entity.GroupJoinRequestEntity;
import com.example.chat.domain.entity.GroupJoinRequestStatus;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.group.CreateInviteLinkRequest;
import com.example.chat.dto.group.GroupInviteLinkResponse;
import com.example.chat.dto.group.InvitePreviewResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.GroupInviteLinkRepository;
import com.example.chat.infrastructure.repository.GroupJoinRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Group invite links: the token is a capability, so every test here is really
 * about who may mint it, what it lets a stranger do, and every way it must
 * stop working.
 */
@SpringBootTest
@Transactional
class GroupInviteLinkTest {

    @Autowired private GroupInviteLinkService linkService;
    @Autowired private ConversationService conversationService;
    @Autowired private GroupJoinRequestService joinRequestService;
    @Autowired private AuthService authService;
    @Autowired private GroupInviteLinkRepository linkRepository;
    @Autowired private GroupJoinRequestRepository joinRequestRepository;
    @Autowired private ConversationMemberRepository memberRepository;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private UserProfileResponse carol;
    private ConversationSummaryResponse group;

    @BeforeEach
    void setUp() {
        alice = user("link-alice");
        bob = user("link-bob");
        carol = user("link-carol");
        group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Link Lab", "Where links get tested", null, List.of(bob.id())));
    }

    @Test
    @DisplayName("an owner can mint a link with an unguessable token and a join URL")
    void ownerCreatesLink() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));

        assertThat(link.token()).matches("^[0-9a-f]{32}$");
        assertThat(link.joinUrl()).isEqualTo("/app#join/" + link.token());
        assertThat(link.groupId()).isEqualTo(group.id());
        assertThat(link.active()).isTrue();
        assertThat(link.usedCount()).isZero();
        assertThat(link.expiresAt()).isNull();
        assertThat(link.maxUses()).isNull();
    }

    @Test
    @DisplayName("plain members and strangers cannot mint links")
    void onlyAdminsCreate() {
        assertThatThrownBy(() -> linkService.create(bob.id(), group.id(), new CreateInviteLinkRequest(null, null)))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);

        assertThatThrownBy(() -> linkService.create(carol.id(), group.id(), new CreateInviteLinkRequest(null, null)))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }

    @Test
    @DisplayName("a moderator manages links once the owner promotes them")
    void moderatorCanManage() {
        conversationService.setMemberRole(group.id(), alice.id(), bob.id(), MemberRole.MODERATOR);

        GroupInviteLinkResponse link = linkService.create(bob.id(), group.id(), new CreateInviteLinkRequest(3600L, 5));
        assertThat(linkService.listActive(bob.id(), group.id())).extracting(GroupInviteLinkResponse::id).contains(link.id());

        linkService.revoke(bob.id(), group.id(), link.id());
        assertThat(linkService.listActive(bob.id(), group.id())).isEmpty();
    }

    @Test
    @DisplayName("joining through a link adds the member and spends one use")
    void joinAddsMember() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, 2));

        UUID joined = linkService.join(carol.id(), link.token());

        assertThat(joined).isEqualTo(group.id());
        assertThat(memberRepository.existsByConversationIdAndUserId(group.id(), carol.id())).isTrue();
        assertThat(linkRepository.findById(link.id())).get()
            .extracting(GroupInviteLinkEntity::getUsedCount).isEqualTo(1);
        assertThat(linkService.listActive(alice.id(), group.id()).get(0).usedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-joining with the same link is a no-op that must not burn a use")
    void rejoinDoesNotSpendAgain() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, 1));
        linkService.join(carol.id(), link.token());

        assertThat(linkService.join(carol.id(), link.token())).isEqualTo(group.id());

        assertThat(linkRepository.findById(link.id())).get()
            .extracting(GroupInviteLinkEntity::getUsedCount).isEqualTo(1);

        // Once someone is in, the group is protected by membership and the
        // block list, not by the state of the link that happened to admit them.
        linkService.revoke(alice.id(), group.id(), link.id());
        assertThat(linkService.join(carol.id(), link.token())).isEqualTo(group.id());
    }

    @Test
    @DisplayName("a valid link opens a private group that self-serve joining refuses")
    void linkBeatsPrivacyFlag() {
        ConversationSummaryResponse secret = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Secret Lab", null, null, List.of(), true));
        GroupInviteLinkResponse link = linkService.create(alice.id(), secret.id(), new CreateInviteLinkRequest(null, null));

        assertThatThrownBy(() -> conversationService.joinGroup(secret.id(), carol.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);

        assertThat(linkService.join(carol.id(), link.token())).isEqualTo(secret.id());
    }

    @Test
    @DisplayName("a revoked link stops working, disappears from the list and previews as invalid")
    void revokedLinkRefused() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));

        linkService.revoke(alice.id(), group.id(), link.id());

        assertThatThrownBy(() -> linkService.join(carol.id(), link.token()))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("no longer valid");
        assertThat(linkService.preview(carol.id(), link.token()).state()).isEqualTo("INVALID");
        assertThat(linkService.listActive(alice.id(), group.id())).isEmpty();
    }

    @Test
    @DisplayName("revoking twice is not silently idempotent")
    void revokeIsOneShot() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));
        linkService.revoke(alice.id(), group.id(), link.id());

        assertThatThrownBy(() -> linkService.revoke(alice.id(), group.id(), link.id()))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("already revoked");
    }

    @Test
    @DisplayName("an expired link is refused without ever telling the holder why")
    void expiredLinkRefused() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(600L, null));
        expire(link.id());

        assertThatThrownBy(() -> linkService.join(carol.id(), link.token()))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("no longer valid");
        assertThat(linkService.preview(carol.id(), link.token()).state()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("the use budget closes the link for the next visitor")
    void useBudgetClosesLink() {
        UserProfileResponse dave = user("link-dave");
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, 1));
        linkService.join(carol.id(), link.token());

        assertThatThrownBy(() -> linkService.join(dave.id(), link.token()))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("no longer valid");
        assertThat(linkService.preview(dave.id(), link.token()).state()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("a blocked user cannot walk back in with a link")
    void blockedUserRefused() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));
        linkService.join(carol.id(), link.token());
        conversationService.blockMember(group.id(), alice.id(), carol.id());

        assertThatThrownBy(() -> linkService.join(carol.id(), link.token()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.USER_BLOCKED);
        assertThat(linkService.preview(carol.id(), link.token()).state()).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("junk tokens are refused before the database is queried")
    void malformedTokensRejected() {
        assertThatThrownBy(() -> linkService.join(carol.id(), "not-a-token"))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("not valid");
        assertThatThrownBy(() -> linkService.join(carol.id(), "00".repeat(15)))
            .isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> linkService.join(carol.id(), null))
            .isInstanceOf(ChatException.class);
        assertThat(linkService.preview(carol.id(), "DEADBEEF").state()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("an unknown but well-formed token previews as invalid")
    void unknownTokenInvalid() {
        assertThat(linkService.preview(carol.id(), "0123456789abcdef0123456789abcdef").state()).isEqualTo("INVALID");
        assertThatThrownBy(() -> linkService.join(carol.id(), "0123456789abcdef0123456789abcdef"))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("not valid");
    }

    @Test
    @DisplayName("preview shows the group card so a visitor knows what they are joining")
    void previewShowsTheGroup() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));

        InvitePreviewResponse preview = linkService.preview(carol.id(), link.token());

        assertThat(preview.state()).isEqualTo("JOINABLE");
        assertThat(preview.groupId()).isEqualTo(group.id());
        assertThat(preview.title()).isEqualTo("Link Lab");
        assertThat(preview.memberCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("members and admins see their own state in the preview")
    void previewReflectsMembership() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));

        assertThat(linkService.preview(bob.id(), link.token()).state()).isEqualTo("ALREADY_MEMBER");

        // A stranger may read the card of a valid link - the token itself is
        // the permission - but joining is what changes state.
        assertThat(linkService.preview(carol.id(), link.token()).state()).isEqualTo("JOINABLE");
    }

    @Test
    @DisplayName("listing links requires admin and refuses strangers")
    void listRequiresAdmin() {
        assertThatThrownBy(() -> linkService.listActive(carol.id(), group.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
        assertThatThrownBy(() -> linkService.listActive(bob.id(), group.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
    }

    @Test
    @DisplayName("a link cannot be revoked through another group's id")
    void revokeIsScopedToItsGroup() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));
        ConversationSummaryResponse other = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Other Lab", null, null, List.of()));

        assertThatThrownBy(() -> linkService.revoke(alice.id(), other.id(), link.id()))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("not found");
        assertThat(linkRepository.findById(link.id())).get()
            .extracting(GroupInviteLinkEntity::isRevoked).isEqualTo(false);
    }

    @Test
    @DisplayName("links are only for groups")
    void directChatsHaveNoLinks() {
        ConversationSummaryResponse direct = conversationService.getOrCreateDirect(alice.id(), bob.id());

        assertThatThrownBy(() -> linkService.create(alice.id(), direct.id(), new CreateInviteLinkRequest(null, null)))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("group conversations");
    }

    @Test
    @DisplayName("joining by link settles a pending join request for the same person")
    void joinClearsPendingRequest() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));
        joinRequestService.requestToJoin(carol.id(), group.id());

        linkService.join(carol.id(), link.token());

        GroupJoinRequestEntity request = joinRequestRepository.findByGroupIdAndUserId(group.id(), carol.id()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(GroupJoinRequestStatus.ACCEPTED);
    }

    @Test
    @DisplayName("token lookup is case-insensitive but the budget is still enforced")
    void uppercaseTokenWorks() {
        GroupInviteLinkResponse link = linkService.create(alice.id(), group.id(), new CreateInviteLinkRequest(null, null));

        assertThat(linkService.join(carol.id(), link.token().toUpperCase())).isEqualTo(group.id());
    }

    /** Time-travel without waiting: push the stored expiry into the past. */
    private void expire(UUID linkId) {
        GroupInviteLinkEntity entity = linkRepository.findById(linkId).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        linkRepository.save(entity);
    }

    private UserProfileResponse user(String name) {
        return authService.register(new RegisterRequest(name, name + "@link.local", "pass123", name), null);
    }
}
