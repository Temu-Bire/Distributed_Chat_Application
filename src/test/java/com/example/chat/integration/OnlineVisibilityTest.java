package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.FriendService;
import com.example.chat.application.PresenceService;
import com.example.chat.application.ProfileQueryService;
import com.example.chat.dto.auth.PublicProfileResponse;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.auth.UserSearchResult;
import com.example.chat.dto.conversation.ConversationMemberResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.friend.FriendRequestResponse;
import com.example.chat.dto.friend.FriendResponse;
import com.example.chat.dto.friend.SendFriendRequest;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Don't share my online status" has to mean offline everywhere, not offline on
 * the profile card and online on the row next to it.
 *
 * <p>Presence lives in memory, so {@code @Transactional} cannot roll it back and
 * every connection here is undone by hand.</p>
 */
@SpringBootTest
@Transactional
class OnlineVisibilityTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ProfileQueryService profileQueryService;

    @Autowired
    private PresenceService presenceService;

    @Autowired
    private UserSettingsRepository settingsRepository;

    private final String sessionId = "vis-session-" + UUID.randomUUID();

    private UserProfileResponse viewer;
    private UserProfileResponse peer;

    @BeforeEach
    void connectPeer() {
        viewer = register("vis_viewer", "Viewer");
        peer = register("vis_peer", "Peer");
        friendService.sendFriendRequest(viewer.id(), new SendFriendRequest(peer.username(), null));
        FriendRequestResponse incoming = friendService.getIncomingRequests(peer.id()).get(0);
        friendService.acceptFriendRequest(peer.id(), incoming.id());
        conversationService.createGroup(viewer.id(), new CreateGroupRequest(
            "Visibility Circle", null, null, List.of(peer.id()), false));
        presenceService.connect(sessionId, "vis_peer_queue", peer.username());
    }

    @AfterEach
    void dropPeer() {
        presenceService.disconnect(sessionId);
    }

    @Test
    @DisplayName("a connected account that shares its status looks online on every surface")
    void sharedStatusShows() {
        assertThat(friendsRow().online()).isTrue();
        assertThat(searchRow().online()).isTrue();
        assertThat(rosterEntry().online()).isTrue();
        assertThat(card().online()).isTrue();
    }

    @Test
    @DisplayName("hiding the status turns every surface offline, not just the card")
    void hiddenStatusStaysHidden() {
        hideStatus();

        assertThat(friendsRow().online())
            .as("the friends list is where a hidden status is most likely to slip out")
            .isFalse();
        assertThat(searchRow().online()).isFalse();
        assertThat(rosterEntry().online()).isFalse();
        assertThat(card().online()).isFalse();
    }

    @Test
    @DisplayName("a hidden status reads as private rather than as offline")
    void cardSaysWhyItIsOffline() {
        hideStatus();

        PublicProfileResponse profile = card();
        assertThat(profile.online()).isFalse();
        assertThat(profile.shareOnlineStatus())
            .as("the flag is what lets the card say 'status hidden' instead of lying about 'offline'")
            .isFalse();
    }

    @Test
    @DisplayName("sharing the status again brings it back on all four surfaces")
    void statusComesBack() {
        hideStatus();
        settingsRepository.findByUserId(peer.id()).ifPresent(settings -> {
            settings.setOnlineVisibility(true);
            settingsRepository.save(settings);
        });

        assertThat(friendsRow().online()).isTrue();
        assertThat(searchRow().online()).isTrue();
        assertThat(rosterEntry().online()).isTrue();
    }

    @Test
    @DisplayName("an account that is simply away is offline but still sharing")
    void disconnectedPeerIsPlainlyOffline() {
        presenceService.disconnect(sessionId);

        assertThat(friendsRow().online()).isFalse();
        assertThat(card().online()).isFalse();
        assertThat(card().shareOnlineStatus()).isTrue();
    }

    private void hideStatus() {
        settingsRepository.findByUserId(peer.id()).ifPresent(settings -> {
            settings.setOnlineVisibility(false);
            settingsRepository.save(settings);
        });
    }

    private FriendResponse friendsRow() {
        return friendService.getFriends(viewer.id()).get(0);
    }

    private UserSearchResult searchRow() {
        return authService.searchUsers("vis_peer", 0, 20, viewer.id()).getContent().get(0);
    }

    private ConversationMemberResponse rosterEntry() {
        UUID groupId = conversationService.getUserConversations(viewer.id()).stream()
            .filter(conversation -> "Visibility Circle".equals(conversation.title()))
            .findFirst().orElseThrow().id();
        return conversationService.getConversationMembers(groupId, viewer.id()).stream()
            .filter(member -> member.userId().equals(peer.id()))
            .findFirst().orElseThrow();
    }

    private PublicProfileResponse card() {
        return profileQueryService.findProfile(viewer.id(), peer.id());
    }

    private UserProfileResponse register(String username, String displayName) {
        return authService.register(
            new RegisterRequest(username, username + "@test.local", "pass123", displayName), null);
    }
}
