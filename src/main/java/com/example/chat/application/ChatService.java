package com.example.chat.application;

import com.example.chat.domain.ChatUser;
import com.example.chat.dto.UserPresenceResponse;
import com.example.chat.exception.ChatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Presence use cases for the WebSocket transport.
 *
 * <p>Carries the rules that were previously embedded in {@code ChatServer}:
 * username policy, duplicate-name rejection, and join/leave announcements.
 * Chat delivery itself lives in the persisted conversation path
 * ({@link PersistentMessageService}); this service only tracks who is
 * connected so the rest of the app can show online status.</p>
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
}
