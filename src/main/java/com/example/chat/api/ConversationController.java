package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.GroupInvitationService;
import com.example.chat.application.GroupInviteLinkService;
import com.example.chat.application.GroupJoinRequestService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.*;
import com.example.chat.dto.group.BlockedUserResponse;
import com.example.chat.dto.group.BlockUserRequest;
import com.example.chat.dto.group.CreateInviteLinkRequest;
import com.example.chat.dto.group.GroupDiscoveryResponse;
import com.example.chat.dto.group.GroupInvitationResponse;
import com.example.chat.dto.group.GroupInviteLinkResponse;
import com.example.chat.dto.group.GroupJoinRequestResponse;
import com.example.chat.dto.group.GroupProfileResponse;
import com.example.chat.dto.group.InvitePreviewResponse;
import com.example.chat.dto.group.InviteToGroupRequest;
import com.example.chat.dto.group.JoinByLinkRequest;
import com.example.chat.domain.entity.MemberRole;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final PersistentMessageService messageService;
    private final AuthService authService;
    private final GroupJoinRequestService groupJoinRequestService;
    private final GroupInvitationService groupInvitationService;
    private final GroupInviteLinkService groupInviteLinkService;

    public ConversationController(ConversationService conversationService,
                                    PersistentMessageService messageService,
                                    AuthService authService,
                                    GroupJoinRequestService groupJoinRequestService,
                                    GroupInvitationService groupInvitationService,
                                    GroupInviteLinkService groupInviteLinkService) {
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.authService = authService;
        this.groupJoinRequestService = groupJoinRequestService;
        this.groupInvitationService = groupInvitationService;
        this.groupInviteLinkService = groupInviteLinkService;
    }

    @GetMapping
    public ResponseEntity<List<ConversationSummaryResponse>> getConversations(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.getUserConversations(user.id()));
    }

    @PostMapping("/direct")
    public ResponseEntity<ConversationSummaryResponse> createDirect(
            Principal principal,
            @Valid @RequestBody CreateDirectRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        ConversationSummaryResponse response = conversationService.getOrCreateDirect(user.id(), request.recipientId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/group")
    public ResponseEntity<ConversationSummaryResponse> createGroup(
            Principal principal,
            @Valid @RequestBody CreateGroupRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        ConversationSummaryResponse response = conversationService.createGroup(user.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationSummaryResponse> getDetails(
            Principal principal,
            @PathVariable("id") UUID conversationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.getConversationDetails(conversationId, user.id()));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<List<ConversationMemberResponse>> getMembers(
            Principal principal,
            @PathVariable("id") UUID conversationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.getConversationMembers(conversationId, user.id()));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<Map<String, String>> addMember(
            Principal principal,
            @PathVariable("id") UUID conversationId,
            @Valid @RequestBody AddMemberRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.addMember(conversationId, user.id(), request);
        return ResponseEntity.ok(Map.of("message", "Member added successfully"));
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Map<String, String>> removeMember(
            Principal principal,
            @PathVariable("id") UUID conversationId,
            @PathVariable("memberId") UUID memberId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.removeMember(conversationId, user.id(), memberId);
        return ResponseEntity.ok(Map.of("message", "Member removed successfully"));
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<Map<String, String>> leaveGroup(
            Principal principal,
            @PathVariable("id") UUID conversationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.removeMember(conversationId, user.id(), user.id());
        return ResponseEntity.ok(Map.of("message", "Left group successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ConversationSummaryResponse> updateGroup(
            Principal principal,
            @PathVariable("id") UUID conversationId,
            @Valid @RequestBody UpdateGroupRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.updateGroup(conversationId, user.id(), request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteGroup(
            Principal principal,
            @PathVariable("id") UUID conversationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.deleteGroup(conversationId, user.id());
        return ResponseEntity.ok(Map.of("message", "Group deleted successfully"));
    }

    @PutMapping("/{id}/archive")
    public ResponseEntity<Map<String, String>> archiveConversation(
            Principal principal,
            @PathVariable("id") UUID conversationId,
            @RequestParam(name = "archive", defaultValue = "true") boolean archive) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.archiveConversation(conversationId, user.id(), archive);
        return ResponseEntity.ok(Map.of("message", archive ? "Conversation archived" : "Conversation unarchived"));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Map<String, String>> markAsRead(
            Principal principal,
            @PathVariable("id") UUID conversationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        messageService.markMessagesRead(conversationId, user.id());
        return ResponseEntity.ok(Map.of("message", "Marked as read"));
    }

    @PostMapping("/groups/{id}/join")
    public ResponseEntity<ConversationSummaryResponse> joinGroup(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.joinGroup(groupId, user.id()));
    }

    @PutMapping("/groups/{id}/members/{userId}/role")
    public ResponseEntity<ConversationMemberResponse> setMemberRole(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @PathVariable("userId") UUID memberId,
            @RequestParam("role") MemberRole role) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.setMemberRole(groupId, user.id(), memberId, role));
    }

    @PostMapping("/groups/{id}/blocks")
    public ResponseEntity<Map<String, String>> blockMember(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @Valid @RequestBody BlockUserRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.blockMember(groupId, user.id(), request.userId());
        return ResponseEntity.ok(Map.of("message", "User blocked from group"));
    }

    @DeleteMapping("/groups/{id}/blocks/{userId}")
    public ResponseEntity<Map<String, String>> unblockMember(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @PathVariable("userId") UUID memberId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        conversationService.unblockMember(groupId, user.id(), memberId);
        return ResponseEntity.ok(Map.of("message", "User unblocked"));
    }

    @GetMapping("/groups/{id}/blocks")
    public ResponseEntity<List<BlockedUserResponse>> getBlockedMembers(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.getBlockedMembers(groupId, user.id()));
    }

    /**
     * The group card. Members read the roster with it; for everybody else the
     * group is public and only its published facts come back.
     */
    @GetMapping("/groups/{id}/profile")
    public ResponseEntity<GroupProfileResponse> getGroupProfile(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(conversationService.getGroupProfile(groupId, user.id()));
    }

    @GetMapping("/groups/discover")
    public ResponseEntity<List<GroupDiscoveryResponse>> discoverGroups(
            Principal principal,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(groupJoinRequestService.discoverGroups(user.id(), query, pageable));
    }

    @PostMapping("/groups/{id}/join-requests")
    public ResponseEntity<GroupJoinRequestResponse> requestToJoinGroup(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        GroupJoinRequestResponse response = groupJoinRequestService.requestToJoin(user.id(), groupId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/groups/{id}/join-requests/{requestId}")
    public ResponseEntity<Map<String, String>> cancelJoinRequest(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @PathVariable("requestId") UUID requestId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        groupJoinRequestService.cancelJoinRequest(user.id(), requestId);
        return ResponseEntity.ok(Map.of("message", "Join request cancelled"));
    }

    @GetMapping("/groups/{id}/join-requests")
    public ResponseEntity<List<GroupJoinRequestResponse>> getGroupJoinRequests(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupJoinRequestService.getPendingRequestsForGroup(groupId, user.id()));
    }

    @PostMapping("/groups/{id}/join-requests/{requestId}/accept")
    public ResponseEntity<GroupJoinRequestResponse> acceptJoinRequest(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @PathVariable("requestId") UUID requestId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupJoinRequestService.acceptJoinRequest(user.id(), requestId));
    }

    @PostMapping("/groups/{id}/join-requests/{requestId}/reject")
    public ResponseEntity<GroupJoinRequestResponse> rejectJoinRequest(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @PathVariable("requestId") UUID requestId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupJoinRequestService.rejectJoinRequest(user.id(), requestId));
    }

    @GetMapping("/join-requests")
    public ResponseEntity<List<GroupJoinRequestResponse>> getUserJoinRequests(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupJoinRequestService.getUserJoinRequests(user.id()));
    }

    @PostMapping("/groups/{id}/invitations")
    public ResponseEntity<List<GroupInvitationResponse>> inviteToGroup(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @Valid @RequestBody InviteToGroupRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        List<GroupInvitationResponse> responses = groupInvitationService.inviteUsers(user.id(), groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    @GetMapping("/invitations")
    public ResponseEntity<List<GroupInvitationResponse>> getUserInvitations(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupInvitationService.getUserInvitations(user.id()));
    }

    @PostMapping("/invitations/{id}/accept")
    public ResponseEntity<GroupInvitationResponse> acceptInvitation(
            Principal principal,
            @PathVariable("id") UUID invitationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupInvitationService.acceptInvitation(user.id(), invitationId));
    }

    @PostMapping("/invitations/{id}/reject")
    public ResponseEntity<GroupInvitationResponse> rejectInvitation(
            Principal principal,
            @PathVariable("id") UUID invitationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupInvitationService.rejectInvitation(user.id(), invitationId));
    }

    @DeleteMapping("/invitations/{id}")
    public ResponseEntity<Map<String, String>> cancelInvitation(
            Principal principal,
            @PathVariable("id") UUID invitationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        groupInvitationService.cancelInvitation(user.id(), invitationId);
        return ResponseEntity.ok(Map.of("message", "Invitation cancelled"));
    }

    @GetMapping("/groups/{id}/invitations")
    public ResponseEntity<List<GroupInvitationResponse>> getGroupInvitations(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupInvitationService.getGroupInvitations(groupId, user.id()));
    }

    @PostMapping("/groups/{id}/invite-links")
    public ResponseEntity<GroupInviteLinkResponse> createInviteLink(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @Valid @RequestBody(required = false) CreateInviteLinkRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        GroupInviteLinkResponse response = groupInviteLinkService.create(user.id(), groupId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/groups/{id}/invite-links")
    public ResponseEntity<List<GroupInviteLinkResponse>> listInviteLinks(
            Principal principal,
            @PathVariable("id") UUID groupId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupInviteLinkService.listActive(user.id(), groupId));
    }

    @DeleteMapping("/groups/{id}/invite-links/{linkId}")
    public ResponseEntity<Map<String, String>> revokeInviteLink(
            Principal principal,
            @PathVariable("id") UUID groupId,
            @PathVariable("linkId") UUID linkId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        groupInviteLinkService.revoke(user.id(), groupId, linkId);
        return ResponseEntity.ok(Map.of("message", "Invite link revoked"));
    }

    /**
     * The token travels in the body, not the query string: a link is a
     * capability, and URLs are the part of a request that ends up in logs.
     */
    @PostMapping("/invite-links/preview")
    public ResponseEntity<InvitePreviewResponse> previewInviteLink(
            Principal principal,
            @Valid @RequestBody JoinByLinkRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(groupInviteLinkService.preview(user.id(), request.token()));
    }

    @PostMapping("/invite-links/join")
    public ResponseEntity<ConversationSummaryResponse> joinByInviteLink(
            Principal principal,
            @Valid @RequestBody JoinByLinkRequest request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        UUID groupId = groupInviteLinkService.join(user.id(), request.token());
        return ResponseEntity.ok(conversationService.getConversationDetails(groupId, user.id()));
    }
}
