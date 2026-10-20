package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.FriendService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.domain.entity.ConversationType;
import com.example.chat.dto.auth.LoginRequest;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.auth.UserSearchResult;
import com.example.chat.dto.conversation.ConversationMemberResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.friend.FriendRequestResponse;
import com.example.chat.dto.friend.FriendResponse;
import com.example.chat.dto.friend.SendFriendRequest;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.dto.settings.UserSettingsDto;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FullE2EChatJourneyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private PersistentMessageService messageService;

    @Autowired
    private UserSettingsRepository settingsRepository;

    @Test
    @DisplayName("Complete E2E user journey: register, login, friend request, private chat, group chat, preferences, logout")
    void completeE2EJourney() {
        MockHttpServletRequest request1 = new MockHttpServletRequest();
        MockHttpServletRequest request2 = new MockHttpServletRequest();
        MockHttpServletRequest request3 = new MockHttpServletRequest();

        // 1. Register two users with different email addresses
        RegisterRequest reg1 = new RegisterRequest("e2e_alice", "alice@journey.com", "Password123!", "Alice Wonderland");
        RegisterRequest reg2 = new RegisterRequest("e2e_bob", "bob@journey.com", "Password123!", "Bob Builder");
        UserProfileResponse alice = authService.register(reg1, request1);
        UserProfileResponse bob = authService.register(reg2, request2);

        // Also register Charlie as an unauthorized 3rd party
        RegisterRequest reg3 = new RegisterRequest("e2e_charlie", "charlie@journey.com", "Password123!", "Charlie Spy");
        UserProfileResponse charlie = authService.register(reg3, request3);

        assertThat(alice.id()).isNotNull();
        assertThat(bob.id()).isNotNull();
        assertThat(charlie.id()).isNotNull();

        // 2. Log in as the first user
        UserProfileResponse loggedInAlice = authService.login(new LoginRequest("alice@journey.com", "Password123!"), request1);
        assertThat(loggedInAlice.username()).isEqualTo("e2e_alice");

        // 3. Search for the second user and send a friend request
        Page<UserSearchResult> searchResults = authService.searchUsers("e2e_bob", 0, 10);
        assertThat(searchResults.getContent()).anyMatch(u -> u.username().equals("e2e_bob"));

        FriendRequestResponse friendReq = friendService.sendFriendRequest(alice.id(), new SendFriendRequest("e2e_bob", null));
        assertThat(friendReq.status().name()).isEqualTo("PENDING");

        // 4. Log in as the second user and accept the request
        UserProfileResponse loggedInBob = authService.login(new LoginRequest("bob@journey.com", "Password123!"), request2);
        assertThat(loggedInBob.username()).isEqualTo("e2e_bob");

        List<FriendRequestResponse> incoming = friendService.getIncomingRequests(bob.id());
        assertThat(incoming).hasSize(1);
        assertThat(incoming.get(0).senderUsername()).isEqualTo("e2e_alice");

        FriendRequestResponse acceptedReq = friendService.acceptFriendRequest(bob.id(), friendReq.id());
        assertThat(acceptedReq.status().name()).isEqualTo("ACCEPTED");

        List<FriendResponse> aliceFriends = friendService.getFriends(alice.id());
        assertThat(aliceFriends).extracting(FriendResponse::username).contains("e2e_bob");

        // 5. Open a private conversation and exchange messages
        ConversationSummaryResponse directChat = conversationService.getOrCreateDirect(alice.id(), bob.id());
        assertThat(directChat.type()).isEqualTo(ConversationType.DIRECT);

        PersistentMessageResponse msg1 = messageService.sendMessage(directChat.id(), alice.id(), alice.username(), "Hello Bob, welcome!");
        PersistentMessageResponse msg2 = messageService.sendMessage(directChat.id(), bob.id(), bob.username(), "Hey Alice, thanks!");

        List<PersistentMessageResponse> history = messageService.getMessages(directChat.id(), alice.id(), 0, 50);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).content()).isEqualTo("Hello Bob, welcome!");
        assertThat(history.get(1).content()).isEqualTo("Hey Alice, thanks!");

        // 6. Edit and delete an authorized message
        PersistentMessageResponse editedMsg = messageService.editMessage(msg1.id(), alice.id(), "Hello Bob, welcome to the app!");
        assertThat(editedMsg.content()).isEqualTo("Hello Bob, welcome to the app!");
        assertThat(editedMsg.edited()).isTrue();

        messageService.deleteMessage(msg2.id(), bob.id());
        List<PersistentMessageResponse> historyAfterDelete = messageService.getMessages(directChat.id(), alice.id(), 0, 50);
        PersistentMessageResponse deletedMsg = historyAfterDelete.stream().filter(m -> m.id().equals(msg2.id())).findFirst().orElseThrow();
        assertThat(deletedMsg.content()).isEqualTo("This message was deleted.");
        assertThat(deletedMsg.deleted()).isTrue();

        // 7. Verify that unauthorized users cannot read or modify those messages
        assertThatThrownBy(() -> messageService.getMessages(directChat.id(), charlie.id(), 0, 50))
            .isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messageService.editMessage(msg1.id(), charlie.id(), "Hacked!"))
            .isInstanceOf(ChatException.class);
        assertThatThrownBy(() -> messageService.deleteMessage(msg1.id(), charlie.id()))
            .isInstanceOf(ChatException.class);

        // 8. Create a group and add eligible friends
        CreateGroupRequest groupReq = new CreateGroupRequest("Alice & Bob Club", "VIP Lounge", null, List.of(bob.id()));
        ConversationSummaryResponse groupChat = conversationService.createGroup(alice.id(), groupReq);
        assertThat(groupChat.isGroup()).isTrue();
        assertThat(groupChat.title()).isEqualTo("Alice & Bob Club");

        // 9. Exchange group messages
        PersistentMessageResponse gMsg1 = messageService.sendMessage(groupChat.id(), alice.id(), alice.username(), "Welcome to our group!");
        PersistentMessageResponse gMsg2 = messageService.sendMessage(groupChat.id(), bob.id(), bob.username(), "Awesome club!");
        List<PersistentMessageResponse> groupHistory = messageService.getMessages(groupChat.id(), bob.id(), 0, 50);
        assertThat(groupHistory).hasSize(2);

        // 10. Verify group-admin and ordinary-member permissions
        // Bob is ordinary member, cannot delete the whole group
        assertThatThrownBy(() -> conversationService.deleteGroup(groupChat.id(), bob.id()))
            .isInstanceOf(ChatException.class);

        // Alice is owner, can update group details
        List<ConversationMemberResponse> members = conversationService.getConversationMembers(groupChat.id(), alice.id());
        assertThat(members).hasSize(2);

        // 11. Change a user preference and confirm it persists after refreshing
        var settingsOpt = settingsRepository.findByUserId(alice.id());
        assertThat(settingsOpt).isPresent();
        var settings = settingsOpt.get();
        settings.setTheme("dark");
        settings.setNotificationsEnabled(false);
        settingsRepository.save(settings);

        var refreshedSettings = settingsRepository.findByUserId(alice.id()).orElseThrow();
        assertThat(refreshedSettings.getTheme()).isEqualTo("dark");
        assertThat(refreshedSettings.isNotificationsEnabled()).isFalse();

        // 12. Log out and verify session clearing
        authService.logout(request1);
        assertThat(request1.getSession(false)).isNull();

        // 13. Log in again and confirm that authorized conversation history remains available
        UserProfileResponse reLoggedInAlice = authService.login(new LoginRequest("alice@journey.com", "Password123!"), request1);
        assertThat(reLoggedInAlice.username()).isEqualTo("e2e_alice");

        List<ConversationSummaryResponse> convs = conversationService.getUserConversations(reLoggedInAlice.id());
        assertThat(convs).extracting(ConversationSummaryResponse::id).contains(directChat.id(), groupChat.id());

        List<PersistentMessageResponse> reloadedMessages = messageService.getMessages(directChat.id(), reLoggedInAlice.id(), 0, 50);
        assertThat(reloadedMessages).hasSize(2);
    }
}
