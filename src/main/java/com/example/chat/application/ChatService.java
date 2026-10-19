package com.example.chat.application;

import com.example.chat.domain.ChatMessage;
import com.example.chat.domain.ChatUser;
import com.example.chat.dto.ChatMessageResponse;
import com.example.chat.dto.UserPresenceResponse;
import com.example.chat.exception.ChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * The chat use cases.
 *
 * <p>Carries the rules that were previously embedded in {@code ChatServer}:
 * username policy, duplicate-name rejection, message-length limits, rejection
 * of private messages to absent users, and join/leave announcements. The
 * original also returned a failure string to the sender; that behaviour is
 * preserved by throwing {@link ChatException}, which the transport layer turns
 * into an {@code ErrorResponse} for the originating connection only.</p>
 *
 * <p>This class has no dependency on WebSocket, STOMP or any controller. It
 * talks to {@link PresenceService} for presence and to {@link MessageRouter}
 * for delivery.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final PresenceService presence;
    private final MessageRouter router;

    public ChatService(PresenceService presence, MessageRouter router) {
        this.presence = presence;
        this.router = router;
    }

    /**
     * Joins a connection to the chat.
     *
     * @param sessionId     server-assigned connection id
     * @param messagingUser server-assigned name for this connection's
     *                      {@code /user/**} destinations
     * @param requestedName the raw display name supplied by the client
     * @return the registered user
     * @throws ChatException if the name is invalid or already taken
     */
    public ChatUser join(String sessionId, String messagingUser, String requestedName) {
        String username = requireValidUsername(requestedName);
        ChatUser user = presence.connect(sessionId, messagingUser, username);

        log.info("User '{}' joined", username);
        router.broadcastPresence(UserPresenceResponse.joined(
                username, presence.onlineUsernames(), Instant.now()));
        return user;
    }

    /**
     * Broadcasts a public message.
     *
     * @param sessionId connection the message came from
     * @param rawContent untrusted body from the client
     * @throws ChatException if the connection has not joined, or the body is invalid
     */
    public void sendPublicMessage(String sessionId, String rawContent) {
        ChatUser sender = presence.requireUser(sessionId);
        String content = requireValidContent(rawContent);

        ChatMessage message = ChatMessage.chat(
                newMessageId(), sender.username(), content, Instant.now());

        router.broadcastMessage(ChatMessageResponse.from(message));
    }

    /**
     * Delivers a private message to one other user, echoing it to the sender.
     *
     * <p>The message is sent to the recipient's and the sender's user
     * destinations. It is never published on the public topic, so its contents
     * cannot leak to anyone else.</p>
     *
     * @param sessionId        connection the message came from
     * @param requestedRecipient untrusted target display name
     * @param rawContent       untrusted body from the client
     * @throws ChatException if the sender has not joined, the body is invalid,
     *                       or the recipient is not connected
     */
    public void sendPrivateMessage(String sessionId, String requestedRecipient, String rawContent) {
        ChatUser sender = presence.requireUser(sessionId);
        String content = requireValidContent(rawContent);
        String recipientName = requireValidRecipient(requestedRecipient);

        ChatUser recipient = presence.findByUsername(recipientName)
                .orElseThrow(() -> new ChatException(ChatException.RECIPIENT_NOT_FOUND,
                        "User '" + recipientName + "' is not online."));

        ChatMessage message = ChatMessage.privateMessage(newMessageId(),
                sender.username(), recipient.username(), content, Instant.now());
        ChatMessageResponse response = ChatMessageResponse.from(message);

        // The body is intentionally absent from this log line.
        log.debug("Private message from '{}' to '{}'", sender.username(), recipient.username());

        router.sendPrivateMessage(recipient, response);
        if (!recipient.sessionId().equals(sender.sessionId())) {
            router.sendPrivateMessage(sender, response);
        }
    }

    /**
     * Removes a disconnected connection and publishes the smaller user list.
     *
     * <p>Safe to call for a connection that never joined; nothing is published
     * in that case.</p>
     */
    public void leave(String sessionId) {
        presence.disconnect(sessionId).ifPresent(user -> {
            log.info("User '{}' left", user.username());
            router.broadcastPresence(UserPresenceResponse.left(
                    user.username(), presence.onlineUsernames(), Instant.now()));
        });
    }

    // ─── Rules ────────────────────────────────────────────────────────────────

    private String requireValidUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (username.isEmpty()) {
            throw new ChatException(ChatException.INVALID_USERNAME, "Username must not be blank.");
        }
        if (username.length() > ChatUser.MAX_USERNAME_LENGTH) {
            throw new ChatException(ChatException.INVALID_USERNAME,
                    "Username must be at most " + ChatUser.MAX_USERNAME_LENGTH + " characters.");
        }
        // Control characters would let a name forge extra lines in a log or in
        // another client's transcript.
        if (username.chars().anyMatch(Character::isISOControl)) {
            throw new ChatException(ChatException.INVALID_USERNAME,
                    "Username contains characters that are not allowed.");
        }
        return username;
    }

    private String requireValidContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "A message must not be empty.");
        }
        if (raw.length() > ChatMessage.MAX_CONTENT_LENGTH) {
            throw new ChatException(ChatException.INVALID_MESSAGE,
                    "Messages must be at most " + ChatMessage.MAX_CONTENT_LENGTH + " characters.");
        }
        return raw;
    }

    private String requireValidRecipient(String raw) {
        String recipient = raw == null ? "" : raw.trim();
        if (recipient.isEmpty()) {
            throw new ChatException(ChatException.RECIPIENT_NOT_FOUND,
                    "Choose someone to send a private message to.");
        }
        return recipient;
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString();
    }
}
