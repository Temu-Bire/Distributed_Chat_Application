package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.*;
import jakarta.validation.Valid;
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
    private final AuthService authService;

    public ConversationController(ConversationService conversationService, AuthService authService) {
        this.conversationService = conversationService;
        this.authService = authService;
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
        conversationService.markAsRead(conversationId, user.id());
        return ResponseEntity.ok(Map.of("message", "Marked as read"));
    }
}
