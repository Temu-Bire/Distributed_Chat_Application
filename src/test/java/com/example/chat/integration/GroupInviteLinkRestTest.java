package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.GroupInviteLinkService;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.group.CreateInviteLinkRequest;
import com.example.chat.dto.group.GroupInviteLinkResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The HTTP surface of invite links: who may call what, and which status each
 * refusal earns. The rules themselves live in
 * {@link com.example.chat.application.GroupInviteLinkTest}; this is about
 * routing, principal resolution and validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GroupInviteLinkRestTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private ConversationService conversationService;
    @Autowired private GroupInviteLinkService linkService;

    private UserProfileResponse owner;
    private UserProfileResponse member;
    private UserProfileResponse stranger;
    private ConversationSummaryResponse group;

    @BeforeEach
    void setUp() {
        owner = authService.register(new RegisterRequest("linkr_owner", "linkr_owner@test.com", "pass123", "Owner"), null);
        member = authService.register(new RegisterRequest("linkr_member", "linkr_member@test.com", "pass123", "Member"), null);
        stranger = authService.register(new RegisterRequest("linkr_stranger", "linkr_stranger@test.com", "pass123", "Stranger"), null);
        group = conversationService.createGroup(owner.id(),
            new CreateGroupRequest("REST Links", "invitation links over http", null, List.of(member.id())));
    }

    @Test
    @DisplayName("anonymous callers cannot mint, preview or spend a link")
    void anonymousRejected() throws Exception {
        mockMvc.perform(post("/api/conversations/groups/" + group.id() + "/invite-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/conversations/invite-links/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"0123456789abcdef0123456789abcdef\"}"))
            .andExpect(status().is4xxClientError());

        mockMvc.perform(post("/api/conversations/invite-links/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"0123456789abcdef0123456789abcdef\"}"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "linkr_owner")
    @DisplayName("create returns 201 with the token and the client-side join URL")
    void createReturnsCreated() throws Exception {
        mockMvc.perform(post("/api/conversations/groups/" + group.id() + "/invite-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateInviteLinkRequest(3600L, 3))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").value(org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{32}$")))
            .andExpect(jsonPath("$.joinUrl").value(org.hamcrest.Matchers.startsWith("/app#join/")))
            .andExpect(jsonPath("$.maxUses").value(3))
            .andExpect(jsonPath("$.usedCount").value(0))
            .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @WithMockUser(username = "linkr_owner")
    @DisplayName("an out-of-range expiry is rejected before anything is stored")
    void validationRejectsSillyLifetimes() throws Exception {
        mockMvc.perform(post("/api/conversations/groups/" + group.id() + "/invite-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInSeconds\":5}"))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/conversations/groups/" + group.id() + "/invite-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxUses\":0}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "linkr_member")
    @DisplayName("a plain member earns 403 on every management route")
    void memberCannotManage() throws Exception {
        mockMvc.perform(post("/api/conversations/groups/" + group.id() + "/invite-links")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/conversations/groups/" + group.id() + "/invite-links"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "linkr_stranger")
    @DisplayName("preview then join over http, and the group is readable afterwards")
    void previewAndJoinRoundTrip() throws Exception {
        GroupInviteLinkResponse link = linkService.create(owner.id(), group.id(), new CreateInviteLinkRequest(null, null));

        mockMvc.perform(post("/api/conversations/invite-links/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + link.token() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("JOINABLE"))
            .andExpect(jsonPath("$.title").value("REST Links"));

        mockMvc.perform(post("/api/conversations/invite-links/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + link.token() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(group.id().toString()))
            .andExpect(jsonPath("$.type").value("GROUP"));

        mockMvc.perform(get("/api/conversations/" + group.id()))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "linkr_stranger")
    @DisplayName("an empty token is a 400 from validation, not a database miss")
    void blankTokenRejected() throws Exception {
        mockMvc.perform(post("/api/conversations/invite-links/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "linkr_owner")
    @DisplayName("revoke answers 200 and the link leaves the list")
    void revokeOverHttp() throws Exception {
        GroupInviteLinkResponse link = linkService.create(owner.id(), group.id(), new CreateInviteLinkRequest(null, null));

        mockMvc.perform(delete("/api/conversations/groups/" + group.id() + "/invite-links/" + link.id()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations/groups/" + group.id() + "/invite-links"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$").isEmpty());
    }
}
