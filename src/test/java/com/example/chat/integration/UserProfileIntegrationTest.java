package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.FriendService;
import com.example.chat.application.PresenceService;
import com.example.chat.domain.entity.UserSettingsEntity;
import com.example.chat.dto.auth.PublicProfileResponse;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UpdateProfileRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.friend.SendFriendRequest;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile card one account can open about another.
 *
 * <p>Two properties matter more than the fields themselves: the response must
 * never carry the target's email address, and a target who switched presence
 * sharing off must not be revealed as online by this path. Both are asserted
 * against the serialized body, because a DTO that merely ignores a field in a
 * test still ships it to the browser.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private UserSettingsRepository settingsRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UserProfileResponse viewer;
    private UserProfileResponse target;
    private UserProfileResponse friendOfBoth;

    /** Presence lives in memory, so @Transactional cannot roll it back. */
    private final List<String> openSessions = new ArrayList<>();

    @BeforeEach
    void registerPeople() {
        viewer = register("uprof_viewer", "Viewer One");
        target = register("uprof_target", "Tar Get");
        friendOfBoth = register("uprof_common", "Common Friend");
    }

    @AfterEach
    void dropPresence() {
        openSessions.forEach(presenceService::disconnect);
        openSessions.clear();
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("the card shows the handle, bio and joined date")
    void cardShowsProfileFields() throws Exception {
        updateProfile(target, new UpdateProfileRequest("Tar Get", "Keeps the roadmap honest.", null));

        bodyOf(target).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(target.id().toString()))
            .andExpect(jsonPath("$.username").value("uprof_target"))
            .andExpect(jsonPath("$.displayName").value("Tar Get"))
            .andExpect(jsonPath("$.bio").value("Keeps the roadmap honest."))
            .andExpect(jsonPath("$.joinedAt").isNotEmpty())
            .andExpect(jsonPath("$.friendshipStatus").value("NONE"));
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("the response never carries the target's email address")
    void cardNeverLeaksEmail() throws Exception {
        String body = mockMvc.perform(get("/api/users/" + target.id()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body)
            .as("the address is how this account logs in, so putting it on a profile card "
                + "hands a stranger half of the credential pair")
            .doesNotContain("uprof_target@test.local")
            .doesNotContain("email");
        assertThat(fieldsOf(body))
            .as("read from the serialized body, not the record, so a future field cannot slip out unnoticed")
            .doesNotContainKeys("email", "password");
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("friendship state travels with the card so the buttons can be right")
    void cardTracksFriendship() throws Exception {
        bodyOf(target).andExpect(jsonPath("$.friendshipStatus").value("NONE"));

        friendService.sendFriendRequest(target.id(), new SendFriendRequest(viewer.username(), null));
        bodyOf(target).andExpect(jsonPath("$.friendshipStatus").value("PENDING_RECEIVED"));

        friendService.acceptFriendRequest(viewer.id(),
            friendService.getIncomingRequests(viewer.id()).get(0).id());
        bodyOf(target).andExpect(jsonPath("$.friendshipStatus").value("FRIENDS"))
            .andExpect(jsonPath("$.mutualFriends").value(0));
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("presence is reported, but only while the target shares it")
    void presenceHonoursTheTargetsSetting() throws Exception {
        connect(target.username());
        bodyOf(target).andExpect(jsonPath("$.online").value(true))
            .andExpect(jsonPath("$.shareOnlineStatus").value(true));

        setOnlineVisibility(target, false);
        bodyOf(target).andExpect(status().isOk())
            .andExpect(jsonPath("$.online").value(false))
            .andExpect(jsonPath("$.shareOnlineStatus").value(false));

        setOnlineVisibility(target, true);
        bodyOf(target).andExpect(jsonPath("$.online").value(true));
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("shared circles are counted, never listed")
    void circlesAreAggregate() throws Exception {
        friendService.sendFriendRequest(viewer.id(), new SendFriendRequest(friendOfBoth.username(), null));
        friendService.sendFriendRequest(target.id(), new SendFriendRequest(friendOfBoth.username(), null));
        acceptAll(friendOfBoth);

        conversationService.createGroup(target.id(),
            new CreateGroupRequest("Circle", "Both of us are in here", null,
                List.of(viewer.id(), friendOfBoth.id()), false));
        conversationService.createGroup(friendOfBoth.id(),
            new CreateGroupRequest("Not shared", null, null, List.of(target.id()), false));

        PublicProfileResponse card = read(mockMvc.perform(get("/api/users/" + target.id()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        assertThat(card.mutualFriends()).as("the one account both of them added").isEqualTo(1);
        assertThat(card.sharedGroups()).as("only the group the viewer is also in").isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("a deactivated account is indistinguishable from a missing one")
    void deactivatedIsNotFound() throws Exception {
        authService.deactivateAccount(principalFor(target), null);

        mockMvc.perform(get("/api/users/" + target.id()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("reading your own card reports SELF and no circles")
    void selfReadIsNotStrange() throws Exception {
        bodyOf(viewer).andExpect(status().isOk())
            .andExpect(jsonPath("$.friendshipStatus").value("SELF"))
            .andExpect(jsonPath("$.mutualFriends").value(0))
            .andExpect(jsonPath("$.sharedGroups").value(0));
    }

    @Test
    @WithMockUser(username = "uprof_viewer")
    @DisplayName("an id that is not a UUID is rejected before it reaches the database")
    void malformedIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/not-a-uuid"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/users/1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an anonymous caller cannot read anybody's card")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/users/" + target.id()))
            .andExpect(status().is4xxClientError());
    }

    private UserProfileResponse register(String username, String displayName) {
        return authService.register(
            new RegisterRequest(username, username + "@test.local", "pass123", displayName), null);
    }

    private void updateProfile(UserProfileResponse who, UpdateProfileRequest request) {
        authService.updateProfile(principalFor(who), request);
    }

    private void setOnlineVisibility(UserProfileResponse who, boolean visible) {
        UserSettingsEntity settings = settingsRepository.findByUserId(who.id()).orElseThrow();
        settings.setOnlineVisibility(visible);
        settingsRepository.save(settings);
    }

    private void connect(String username) {
        String sessionId = "uprof-session-" + username;
        openSessions.add(sessionId);
        presenceService.connect(sessionId, username, username);
    }

    /** The caller has sent requests to everyone they now shares a profile with. */
    private void acceptAll(UserProfileResponse who) {
        friendService.getIncomingRequests(who.id())
            .forEach(request -> friendService.acceptFriendRequest(who.id(), request.id()));
    }

    private ResultActions bodyOf(UserProfileResponse who) throws Exception {
        return mockMvc.perform(get("/api/users/" + who.id()));
    }

    private PublicProfileResponse read(String body) throws Exception {
        return objectMapper.readValue(body, PublicProfileResponse.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fieldsOf(String body) throws Exception {
        return objectMapper.readValue(body, Map.class);
    }

    private static Principal principalFor(UserProfileResponse user) {
        return () -> user.username();
    }
}
