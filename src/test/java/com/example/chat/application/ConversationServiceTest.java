package com.example.chat.application;

import com.example.chat.domain.entity.ConversationType;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.*;
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
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private UserProfileResponse charlie;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("conv_alice", "conv_alice@test.com", "pass123", "Alice C"), null);
        bob = authService.register(new RegisterRequest("conv_bob", "conv_bob@test.com", "pass123", "Bob C"), null);
        charlie = authService.register(new RegisterRequest("conv_charlie", "conv_charlie@test.com", "pass123", "Charlie C"), null);
    }

    @Test
    @DisplayName("create direct conversation returns new conversation with both members")
    void createDirectConversation() {
        ConversationSummaryResponse conv = conversationService.getOrCreateDirect(alice.id(), bob.id());

        assertThat(conv.id()).isNotNull();
        assertThat(conv.type()).isEqualTo(ConversationType.DIRECT);
        assertThat(conv.title()).isEqualTo("Bob C");

        List<ConversationMemberResponse> members = conversationService.getConversationMembers(conv.id(), alice.id());
        assertThat(members).hasSize(2);
        assertThat(members).extracting(ConversationMemberResponse::username).contains("conv_alice", "conv_bob");
    }

    @Test
    @DisplayName("calling getOrCreateDirect twice returns the existing conversation idempotently")
    void getOrCreateDirectIdempotent() {
        ConversationSummaryResponse first = conversationService.getOrCreateDirect(alice.id(), bob.id());
        ConversationSummaryResponse second = conversationService.getOrCreateDirect(alice.id(), bob.id());
        ConversationSummaryResponse reverse = conversationService.getOrCreateDirect(bob.id(), alice.id());

        assertThat(first.id()).isEqualTo(second.id());
        assertThat(first.id()).isEqualTo(reverse.id());
    }

    @Test
    @DisplayName("direct conversation with self is rejected")
    void directConversationWithSelfFails() {
        assertThatThrownBy(() -> conversationService.getOrCreateDirect(alice.id(), alice.id()))
            .isInstanceOf(ChatException.class);
    }

    @Test
    @DisplayName("create group conversation sets creator as OWNER")
    void createGroupConversation() {
        CreateGroupRequest req = new CreateGroupRequest("Dev Team", "Developers channel", null, List.of(bob.id(), charlie.id()));
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(), req);

        assertThat(group.id()).isNotNull();
        assertThat(group.title()).isEqualTo("Dev Team");
        assertThat(group.isGroup()).isTrue();

        List<ConversationMemberResponse> members = conversationService.getConversationMembers(group.id(), alice.id());
        assertThat(members).hasSize(3);

        ConversationMemberResponse aliceMember = members.stream()
            .filter(m -> m.userId().equals(alice.id()))
            .findFirst().orElseThrow();
        assertThat(aliceMember.role()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("group owner can add and remove members")
    void addAndRemoveGroupMembers() {
        CreateGroupRequest req = new CreateGroupRequest("Admins", "Admins chat", null, List.of(bob.id()));
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(), req);

        // Add Charlie
        conversationService.addMember(group.id(), alice.id(), new AddMemberRequest(charlie.id()));
        List<ConversationMemberResponse> membersAfterAdd = conversationService.getConversationMembers(group.id(), alice.id());
        assertThat(membersAfterAdd).hasSize(3);

        // Remove Charlie
        conversationService.removeMember(group.id(), alice.id(), charlie.id());
        List<ConversationMemberResponse> membersAfterRemove = conversationService.getConversationMembers(group.id(), alice.id());
        assertThat(membersAfterRemove).hasSize(2);
        assertThat(membersAfterRemove).extracting(ConversationMemberResponse::username).doesNotContain("conv_charlie");
    }

    @Test
    @DisplayName("non-owner cannot remove other members from group")
    void nonOwnerCannotRemoveMembers() {
        CreateGroupRequest req = new CreateGroupRequest("Team", "Team chat", null, List.of(bob.id(), charlie.id()));
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(), req);

        // Bob tries to remove Charlie -> forbidden
        assertThatThrownBy(() -> conversationService.removeMember(group.id(), bob.id(), charlie.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.FORBIDDEN);
    }

    @Test
    @DisplayName("member can leave group by removing themselves")
    void memberCanLeaveGroup() {
        CreateGroupRequest req = new CreateGroupRequest("Community", "General", null, List.of(bob.id()));
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(), req);

        // Bob leaves voluntarily
        conversationService.removeMember(group.id(), bob.id(), bob.id());

        List<ConversationMemberResponse> members = conversationService.getConversationMembers(group.id(), alice.id());
        assertThat(members).hasSize(1);
    }

    @Test
    @DisplayName("archive and unarchive updates conversation visibility")
    void archiveAndUnarchive() {
        ConversationSummaryResponse conv = conversationService.getOrCreateDirect(alice.id(), bob.id());

        conversationService.archiveConversation(conv.id(), alice.id(), true);
        List<ConversationSummaryResponse> activeList = conversationService.getUserConversations(alice.id());
        assertThat(activeList).extracting(ConversationSummaryResponse::id).doesNotContain(conv.id());

        conversationService.archiveConversation(conv.id(), alice.id(), false);
        List<ConversationSummaryResponse> restoredList = conversationService.getUserConversations(alice.id());
        assertThat(restoredList).extracting(ConversationSummaryResponse::id).contains(conv.id());
    }
}
