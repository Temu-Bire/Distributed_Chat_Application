package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.message.EditMessageDto;
import com.example.chat.dto.message.ForwardMessageDto;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.dto.message.PinDto;
import com.example.chat.dto.message.ReactionDto;
import com.example.chat.dto.message.SendMessageDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class MessageController {

    private final PersistentMessageService messageService;
    private final AuthService authService;

    public MessageController(PersistentMessageService messageService, AuthService authService) {
        this.messageService = messageService;
        this.authService = authService;
    }

    @GetMapping("/api/conversations/{conversationId}/messages")
    public ResponseEntity<List<PersistentMessageResponse>> getMessages(
            Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(messageService.getMessages(conversationId, user.id(), page, size));
    }

    @PostMapping("/api/conversations/{conversationId}/messages")
    public ResponseEntity<PersistentMessageResponse> sendMessage(
            Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody SendMessageDto request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        PersistentMessageResponse response = messageService.sendMessage(
            conversationId, user.id(), user.username(), request.content(),
            request.replyToId(), request.clientMessageId(), null, request.attachment()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/conversations/{conversationId}/messages/forward")
    public ResponseEntity<PersistentMessageResponse> forwardMessage(
            Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @Valid @RequestBody ForwardMessageDto request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        PersistentMessageResponse response = messageService.forwardMessage(
            conversationId, user.id(), user.username(), request.messageId(), request.clientMessageId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/api/messages/{messageId}")
    public ResponseEntity<PersistentMessageResponse> editMessage(
            Principal principal,
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody EditMessageDto request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(messageService.editMessage(messageId, user.id(), request.content()));
    }

    @DeleteMapping("/api/messages/{messageId}")
    public ResponseEntity<Map<String, String>> deleteMessage(
            Principal principal,
            @PathVariable("messageId") UUID messageId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        messageService.deleteMessage(messageId, user.id());
        return ResponseEntity.ok(Map.of("message", "Message deleted successfully"));
    }

    @PostMapping("/api/messages/{messageId}/reactions")
    public ResponseEntity<PersistentMessageResponse> toggleReaction(
            Principal principal,
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody ReactionDto request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(messageService.toggleReaction(messageId, user.id(), request.emoji()));
    }

    @PutMapping("/api/messages/{messageId}/pin")
    public ResponseEntity<PersistentMessageResponse> pinMessage(
            Principal principal,
            @PathVariable("messageId") UUID messageId,
            @Valid @RequestBody PinDto request) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(messageService.setPinned(messageId, user.id(), request.pinned()));
    }

    @GetMapping("/api/conversations/{conversationId}/pinned")
    public ResponseEntity<List<PersistentMessageResponse>> getPinned(
            Principal principal,
            @PathVariable("conversationId") UUID conversationId) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(messageService.getPinnedMessages(conversationId, user.id()));
    }

    @GetMapping("/api/conversations/{conversationId}/messages/search")
    public ResponseEntity<List<PersistentMessageResponse>> searchMessages(
            Principal principal,
            @PathVariable("conversationId") UUID conversationId,
            @RequestParam("q") String query,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        return ResponseEntity.ok(messageService.searchMessages(conversationId, user.id(), query, limit));
    }
}
