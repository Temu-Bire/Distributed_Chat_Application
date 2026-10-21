package com.example.chat.websocket;

import com.example.chat.application.AuthService;
import com.example.chat.application.ChatService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.dto.ChatMessageRequest;
import com.example.chat.dto.JoinRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.exception.ChatException;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * Inbound STOMP adapter.
 *
 * <p>Its whole job is to translate a STOMP frame into a call on the
 * application services: pull the connection identity out of the session
 * headers, hand the validated payload over, and nothing else. There is no
 * business logic here, and no handler trusts a sender field from the
 * payload.</p>
 *
 * <pre>
 * /app/chat.join                     {"username":"alice"}
 * /app/conversations/{id}/send       {"content":"hello"}
 * </pre>
 */
@Controller
public class ChatWebSocketController {

    private final ChatService chatService;
    private final AuthService authService;
    private final PersistentMessageService persistentMessageService;

    public ChatWebSocketController(ChatService chatService,
                                   AuthService authService,
                                   PersistentMessageService persistentMessageService) {
        this.chatService = chatService;
        this.authService = authService;
        this.persistentMessageService = persistentMessageService;
    }

    /** Registers this connection under a display name. */
    @MessageMapping("/chat.join")
    public void join(@Valid @Payload JoinRequest request, SimpMessageHeaderAccessor headers) {
        chatService.join(sessionId(headers), messagingUser(headers), request.username());
    }

    /** Sends a message into a persisted conversation over the WebSocket thread pool. */
    @MessageMapping("/conversations/{conversationId}/send")
    public void sendToConversation(@DestinationVariable UUID conversationId,
                                   @Valid @Payload ChatMessageRequest request,
                                   Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        persistentMessageService.sendMessage(conversationId, user.id(), user.username(), request.content());
    }

    private static String sessionId(SimpMessageHeaderAccessor headers) {
        String sessionId = headers.getSessionId();
        if (sessionId == null) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Missing STOMP session.");
        }
        return sessionId;
    }

    private static String messagingUser(SimpMessageHeaderAccessor headers) {
        Principal user = headers.getUser();
        if (user == null) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Missing connection identity.");
        }
        return user.getName();
    }
}
