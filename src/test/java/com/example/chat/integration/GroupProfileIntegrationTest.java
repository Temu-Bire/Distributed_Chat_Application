package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.GroupJoinRequestService;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.AddMemberRequest;
import com.example.chat.dto.conversation.ConversationMemberResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.group.GroupProfileResponse;
import com.example.chat.domain.entity.MemberRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The group card, and the line between what a group advertises and what it
 * keeps.
 *
 * <p>Discovery already publishes a public group's name, description and
 * headcount, so the card may repeat those to strangers. The roster is the part
 * that is not public: who is inside a group is information about its members,
 * not about the group, and this is the endpoint most likely to get that wrong.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GroupProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private GroupJoinRequestService joinRequestService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserProfileResponse owner;
    private UserProfileResponse member;
    private UserProfileResponse stranger;
    private UUID publicGroup;
    private UUID privateGroup;

    @BeforeEach
    void buildGroups() {
        owner = register("gprof_owner", "Group Owner");
        member = register("gprof_member", "Plain Member");
        stranger = register("gprof_stranger", "Nobody");

        publicGroup = conversationService.createGroup(owner.id(), new CreateGroupRequest(
            "Public Standup", "Daily fifteen minutes", null, List.of(member.id()), false)).id();
        privateGroup = conversationService.createGroup(owner.id(), new CreateGroupRequest(
            "Secret Summit", "Names of the people we are surprising", null, List.of(member.id()), true)).id();
    }

    @Test
    @WithMockUser(username = "gprof_member")
    @DisplayName("a plain member reads the whole card, roster and roles included")
    void memberSeesRoster() throws Exception {
        GroupProfileResponse card = read(mockMvc.perform(
            get("/api/conversations/groups/" + publicGroup + "/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Public Standup"))
            .andExpect(jsonPath("$.description").value("Daily fifteen minutes"))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.membershipStatus").value("MEMBER"))
            .andReturn());

        assertThat(card.isPrivate()).isFalse();
        assertThat(card.myRole()).isEqualTo(MemberRole.MEMBER);
        assertThat(card.memberCount()).isEqualTo(2);
        assertThat(card.members()).extracting(ConversationMemberResponse::username)
            .containsExactlyInAnyOrder("gprof_owner", "gprof_member");
        assertThat(card.members())
            .as("the role is what tells a member who can moderate them")
            .extracting(member -> member.username() + ":" + member.role())
            .containsExactlyInAnyOrder("gprof_owner:OWNER", "gprof_member:MEMBER");
    }

    @Test
    @WithMockUser(username = "gprof_owner")
    @DisplayName("the owner's card says so")
    void ownerSeesOwnRole() throws Exception {
        mockMvc.perform(get("/api/conversations/groups/" + publicGroup + "/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.myRole").value("OWNER"));
    }

    @Test
    @WithMockUser(username = "gprof_stranger")
    @DisplayName("a stranger on a public group gets the facts, not the names")
    void publicCardHidesTheRoster() throws Exception {
        GroupProfileResponse card = read(mockMvc.perform(
            get("/api/conversations/groups/" + publicGroup + "/profile"))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(card.membershipStatus()).isEqualTo("NONE");
        assertThat(card.myRole()).isNull();
        assertThat(card.memberCount()).isEqualTo(2);
        assertThat(card.members())
            .as("the roster is information about the two people inside, not about the group")
            .isEmpty();
    }

    @Test
    @WithMockUser(username = "gprof_stranger")
    @DisplayName("a private group is not readable, and says so without naming it")
    void privateCardIsRefused() throws Exception {
        mockMvc.perform(get("/api/conversations/groups/" + privateGroup + "/profile"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("NOT_MEMBER"));
    }

    @Test
    @WithMockUser(username = "gprof_stranger")
    @DisplayName("a pending join request shows on the card")
    void pendingRequestShows() throws Exception {
        joinRequestService.requestToJoin(stranger.id(), publicGroup);

        mockMvc.perform(get("/api/conversations/groups/" + publicGroup + "/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.membershipStatus").value("PENDING"))
            .andExpect(jsonPath("$.members").isEmpty());
    }

    @Test
    @WithMockUser(username = "gprof_stranger")
    @DisplayName("a blocked user is told they are blocked and still gets no roster")
    void blockedUserSeesItSelf() throws Exception {
        // Blocking removes a member, so there has to be a membership to remove.
        conversationService.addMember(publicGroup, owner.id(), new AddMemberRequest(stranger.id()));
        conversationService.blockMember(publicGroup, owner.id(), stranger.id());

        GroupProfileResponse card = read(mockMvc.perform(
            get("/api/conversations/groups/" + publicGroup + "/profile"))
            .andExpect(status().isOk())
            .andReturn());

        assertThat(card.membershipStatus()).isEqualTo("BLOCKED");
        assertThat(card.members()).isEmpty();
        assertThat(card.memberCount()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "gprof_stranger")
    @DisplayName("discovery advertises public groups and nothing else")
    void discoveryOmitsPrivateGroups() throws Exception {
        String body = mockMvc.perform(get("/api/conversations/groups/discover"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Public Standup");
        assertThat(body)
            .as("a private group's name and description are not advertising space")
            .doesNotContain("Secret Summit")
            .doesNotContain("Names of the people we are surprising");
    }

    @Test
    @WithMockUser(username = "gprof_member")
    @DisplayName("a direct conversation has no group card")
    void directHasNoCard() throws Exception {
        UUID dm = conversationService.getOrCreateDirect(member.id(), owner.id()).id();

        mockMvc.perform(get("/api/conversations/groups/" + dm + "/profile"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_COMMAND"));
    }

    @Test
    @WithMockUser(username = "gprof_member")
    @DisplayName("an unknown group id is a plain 404")
    void unknownGroupIsNotFound() throws Exception {
        mockMvc.perform(get("/api/conversations/groups/" + UUID.randomUUID() + "/profile"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("nobody outside the group card is anonymous")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/conversations/groups/" + publicGroup + "/profile"))
            .andExpect(status().is4xxClientError());
    }

    private UserProfileResponse register(String username, String displayName) {
        return authService.register(
            new RegisterRequest(username, username + "@test.local", "pass123", displayName), null);
    }

    private GroupProfileResponse read(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), GroupProfileResponse.class);
    }
}
