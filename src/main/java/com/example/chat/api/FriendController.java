package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.application.FriendService;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.friend.FriendRequestResponse;
import com.example.chat.dto.friend.FriendResponse;
import com.example.chat.dto.friend.FriendshipStatusResponse;
import com.example.chat.dto.friend.SendFriendRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final FriendService friendService;
    private final AuthService authService;

    public FriendController(FriendService friendService, AuthService authService) {
        this.friendService = friendService;
        this.authService = authService;
    }

    @PostMapping("/requests")
    public ResponseEntity<FriendRequestResponse> sendFriendRequest(
            Principal principal,
            @Valid @RequestBody SendFriendRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        FriendRequestResponse response = friendService.sendFriendRequest(user.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/requests/incoming")
    public ResponseEntity<List<FriendRequestResponse>> getIncomingRequests(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.getIncomingRequests(user.id()));
    }

    @GetMapping("/requests/outgoing")
    public ResponseEntity<List<FriendRequestResponse>> getOutgoingRequests(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.getOutgoingRequests(user.id()));
    }

    @PostMapping("/requests/{id}/accept")
    public ResponseEntity<FriendRequestResponse> acceptFriendRequest(
            Principal principal,
            @PathVariable("id") UUID requestId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.acceptFriendRequest(user.id(), requestId));
    }

    @PostMapping("/requests/{id}/reject")
    public ResponseEntity<FriendRequestResponse> rejectFriendRequest(
            Principal principal,
            @PathVariable("id") UUID requestId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.rejectFriendRequest(user.id(), requestId));
    }

    @DeleteMapping("/requests/{id}")
    public ResponseEntity<FriendRequestResponse> cancelFriendRequest(
            Principal principal,
            @PathVariable("id") UUID requestId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.cancelFriendRequest(user.id(), requestId));
    }

    @GetMapping
    public ResponseEntity<List<FriendResponse>> getFriends(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.getFriends(user.id()));
    }

    @DeleteMapping("/{friendId}")
    public ResponseEntity<Map<String, String>> removeFriend(
            Principal principal,
            @PathVariable("friendId") UUID friendId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        friendService.removeFriend(user.id(), friendId);
        return ResponseEntity.ok(Map.of("message", "Friend removed successfully"));
    }

    @GetMapping("/status/{targetUserId}")
    public ResponseEntity<FriendshipStatusResponse> getFriendshipStatus(
            Principal principal,
            @PathVariable("targetUserId") UUID targetUserId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(friendService.getFriendshipStatus(user.id(), targetUserId));
    }
}
