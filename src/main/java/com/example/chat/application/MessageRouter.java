package com.example.chat.application;

import com.example.chat.domain.ChatUser;
import com.example.chat.dto.ChatMessageResponse;
import com.example.chat.dto.ErrorResponse;
import com.example.chat.dto.UserPresenceResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The only class that knows STOMP destination names.
 *
 * <p>Routing is deliberately separated from presence and from the chat rules.
 * {@link ChatService} decides <em>what</em> should happen; this class decides
 * <em>where</em> it is published, which keeps every destination in one place
 * and lets the broker be replaced without touching business logic.</p>
 *
 * <h3>Destinations</h3>
 * <pre>
 * /topic/public            ChatMessageResponse   public CHAT messages
 * /topic/users             UserPresenceResponse  user list and join/leave events
 * /user/queue/private      ChatMessageResponse   private messages, one connection
 * /user/queue/errors       ErrorResponse         failures, one connection
 * </pre>
 */
@Component
public class MessageRouter {

    public static final String TOPIC_PUBLIC = "/topic/public";
    public static final String TOPIC_USERS = "/topic/users";
    public static final String QUEUE_PRIVATE = "/queue/private";
    public static final String QUEUE_ERRORS = "/queue/errors";

    private final SimpMessagingTemplate messaging;

    public MessageRouter(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** Publishes a public message to every subscriber. */
    public void broadcastMessage(ChatMessageResponse message) {
        messaging.convertAndSend(TOPIC_PUBLIC, message);
    }

    /** Publishes the user list and join/leave event to every subscriber. */
    public void broadcastPresence(UserPresenceResponse presence) {
        messaging.convertAndSend(TOPIC_USERS, presence);
    }

    /**
     * Delivers a message to exactly one connection.
     *
     * <p>Addressed with the connection's {@code messagingUser} rather than its
     * display name, so delivery cannot spill over to another connection that
     * happens to share (or later reclaims) a name.</p>
     */
    public void sendPrivateMessage(ChatUser recipient, ChatMessageResponse message) {
        messaging.convertAndSendToUser(recipient.messagingUser(), QUEUE_PRIVATE, message);
    }

    /** Delivers a failure to exactly one connection. */
    public void sendError(String messagingUser, ErrorResponse error) {
        messaging.convertAndSendToUser(messagingUser, QUEUE_ERRORS, error);
    }
}
