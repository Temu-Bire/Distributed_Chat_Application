package com.example.chat.application;

import com.example.chat.domain.entity.MemberRole;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationMemberResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
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
class ConversationOwnershipTransferTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AuthService authService;

    private UserProfileResponse creator;
    private UserProfileResponse member1;
    private UserProfileResponse member2;

    @BeforeEach
    void setUp() {
        creator = authService.register(new RegisterRequest("owner_u1", "owner_u1@test.com", "pass123", "Owner User"), null);
        member1 = authService.register(new RegisterRequest("owner_u2", "owner_u2@test.com", "pass123", "Member Two"), null);
        member2 = authService.register(new RegisterRequest("owner_u3", "owner_u3@test.com", "pass123", "Member Three"), null);
    }

    @Test
    @DisplayName("when owner leaves group with other members, ownership transfers to the next member")
    void ownerLeavesGroupOwnershipTransfers() {
        CreateGroupRequest createReq = new CreateGroupRequest("Dev Team", "Discussion", null, List.of(member1.id(), member2.id()));
        ConversationSummaryResponse group = conversationService.createGroup(creator.id(), createReq);

        // Creator leaves
        conversationService.removeMember(group.id(), creator.id(), creator.id());

        // Verify remaining members and new owner
        List<ConversationMemberResponse> members = conversationService.getConversationMembers(group.id(), member1.id());
        assertThat(members).hasSize(2);

        ConversationMemberResponse newOwner = members.stream()
            .filter(m -> m.userId().equals(member1.id()))
            .findFirst().orElseThrow();
        assertThat(newOwner.role()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("when last member leaves group, group is deleted")
    void lastMemberLeavesGroupIsDeleted() {
        CreateGroupRequest createReq = new CreateGroupRequest("Solo Team", "Solo", null, List.of());
        ConversationSummaryResponse group = conversationService.createGroup(creator.id(), createReq);

        // Creator leaves solo group
        conversationService.removeMember(group.id(), creator.id(), creator.id());

        assertThatThrownBy(() -> conversationService.getConversationDetails(group.id(), creator.id()))
            .isInstanceOf(ChatException.class);
    }
}
