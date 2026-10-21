package com.example.chat.application;

import com.example.chat.domain.entity.FriendRequestStatus;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.friend.FriendRequestResponse;
import com.example.chat.dto.friend.FriendResponse;
import com.example.chat.dto.friend.FriendshipStatusResponse;
import com.example.chat.dto.friend.SendFriendRequest;
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
class FriendServiceTest {

    @Autowired
    private FriendService friendService;

    @Autowired
    private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private UserProfileResponse charlie;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("fr_alice", "fr_alice@test.com", "pass123", "Alice F"), null);
        bob = authService.register(new RegisterRequest("fr_bob", "fr_bob@test.com", "pass123", "Bob F"), null);
        charlie = authService.register(new RegisterRequest("fr_charlie", "fr_charlie@test.com", "pass123", "Charlie F"), null);
    }

    @Test
    @DisplayName("cannot send friend request to self")
    void cannotSendRequestToSelf() {
        assertThatThrownBy(() -> friendService.sendFriendRequest(alice.id(), new SendFriendRequest(alice.username(), null)))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("You cannot send a friend request to yourself");
    }

    @Test
    @DisplayName("sending friend request creates PENDING request")
    void sendFriendRequestSuccess() {
        FriendRequestResponse res = friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));

        assertThat(res.id()).isNotNull();
        assertThat(res.senderId()).isEqualTo(alice.id());
        assertThat(res.receiverId()).isEqualTo(bob.id());
        assertThat(res.status()).isEqualTo(FriendRequestStatus.PENDING);

        List<FriendRequestResponse> incoming = friendService.getIncomingRequests(bob.id());
        assertThat(incoming).hasSize(1);
        assertThat(incoming.get(0).senderUsername()).isEqualTo(alice.username());

        List<FriendRequestResponse> outgoing = friendService.getOutgoingRequests(alice.id());
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0).receiverUsername()).isEqualTo(bob.username());
    }

    @Test
    @DisplayName("prevent duplicate friend request")
    void duplicateFriendRequestPrevented() {
        friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));

        assertThatThrownBy(() -> friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null)))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("already");
    }

    @Test
    @DisplayName("friend request carries the sender's message")
    void friendRequestCarriesMessage() {
        FriendRequestResponse res = friendService.sendFriendRequest(
            alice.id(), new SendFriendRequest(bob.username(), null, "  Hi Bob, it's Alice  "));
        assertThat(res.message()).isEqualTo("Hi Bob, it's Alice");

        List<FriendRequestResponse> incoming = friendService.getIncomingRequests(bob.id());
        assertThat(incoming).hasSize(1);
        assertThat(incoming.get(0).message()).isEqualTo("Hi Bob, it's Alice");
    }

    @Test
    @DisplayName("accepting friend request creates mutual friendships")
    void acceptFriendRequestSuccess() {
        FriendRequestResponse req = friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));

        FriendRequestResponse accepted = friendService.acceptFriendRequest(bob.id(), req.id());
        assertThat(accepted.status()).isEqualTo(FriendRequestStatus.ACCEPTED);

        List<FriendResponse> aliceFriends = friendService.getFriends(alice.id());
        assertThat(aliceFriends).hasSize(1);
        assertThat(aliceFriends.get(0).username()).isEqualTo(bob.username());

        List<FriendResponse> bobFriends = friendService.getFriends(bob.id());
        assertThat(bobFriends).hasSize(1);
        assertThat(bobFriends.get(0).username()).isEqualTo(alice.username());

        FriendshipStatusResponse status = friendService.getFriendshipStatus(alice.id(), bob.id());
        assertThat(status.status()).isEqualTo("FRIENDS");
    }

    @Test
    @DisplayName("unauthorized user cannot accept someone else's friend request")
    void unauthorizedUserCannotAccept() {
        FriendRequestResponse req = friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));

        assertThatThrownBy(() -> friendService.acceptFriendRequest(charlie.id(), req.id()))
            .isInstanceOf(ChatException.class)
            .hasMessageContaining("not authorized");
    }

    @Test
    @DisplayName("rejecting friend request marks status as REJECTED")
    void rejectFriendRequestSuccess() {
        FriendRequestResponse req = friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));

        FriendRequestResponse rejected = friendService.rejectFriendRequest(bob.id(), req.id());
        assertThat(rejected.status()).isEqualTo(FriendRequestStatus.REJECTED);

        assertThat(friendService.getFriends(alice.id())).isEmpty();
        assertThat(friendService.getFriends(bob.id())).isEmpty();
    }

    @Test
    @DisplayName("cancelling outgoing friend request marks status as CANCELLED")
    void cancelFriendRequestSuccess() {
        FriendRequestResponse req = friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));

        FriendRequestResponse cancelled = friendService.cancelFriendRequest(alice.id(), req.id());
        assertThat(cancelled.status()).isEqualTo(FriendRequestStatus.CANCELLED);

        assertThat(friendService.getIncomingRequests(bob.id())).isEmpty();
    }

    @Test
    @DisplayName("removing a friend breaks mutual friendship")
    void removeFriendSuccess() {
        FriendRequestResponse req = friendService.sendFriendRequest(alice.id(), new SendFriendRequest(bob.username(), null));
        friendService.acceptFriendRequest(bob.id(), req.id());

        friendService.removeFriend(alice.id(), bob.id());

        assertThat(friendService.getFriends(alice.id())).isEmpty();
        assertThat(friendService.getFriends(bob.id())).isEmpty();
        assertThat(friendService.getFriendshipStatus(alice.id(), bob.id()).status()).isEqualTo("NONE");
    }
}
