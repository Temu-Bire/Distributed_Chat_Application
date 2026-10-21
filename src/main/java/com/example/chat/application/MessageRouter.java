package com.example.chat.application;

import com.example.chat.dto.ErrorResponse;
import com.example.chat.dto.UserPresenceResponse;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
 * /app/chat.join                     (SEND)   register a display name
 * /app/conversations/{id}/send       (SEND)   persist + fan out a message
 *
 * /topic/users             UserPresenceResponse  user list and join/leave events
 * /topic/conversations/{id}          PersistentMessageResponse  conversation fan-out
 * /user/queue/errors       ErrorResponse         failures, one connection
 * </pre>
 *
 * <p>The two composing prefixes ({@link #APP_PREFIX} and {@link #USER_PREFIX})
 * are held here as well, and are the values handed to the broker in
 * {@code WebSocketConfig}. That makes this class the single source of truth
 * for the wire contract the browser has to match, which is what
 * {@code FrontendContractTest} checks.</p>
 */
@Component
public class MessageRouter {

    public static final String APP_PREFIX = "/app";
    public static final String USER_PREFIX = "/user";

    public static final String TOPIC_USERS = "/topic/users";
    public static final String QUEUE_ERRORS = "/queue/errors";
    public static final String TOPIC_CONVERSATIONS = "/topic/conversations";

    public static String conversationTopic(UUID conversationId) {
        return TOPIC_CONVERSATIONS + "/" + conversationId;
    }

    private final SimpMessagingTemplate messaging;

    public MessageRouter(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    /** Publishes the user list and join/leave event to every subscriber. */
    public void broadcastPresence(UserPresenceResponse presence) {
        messaging.convertAndSend(TOPIC_USERS, presence);
    }

    /** Delivers a failure to exactly one connection. */
    public void sendError(String messagingUser, ErrorResponse error) {
        messaging.convertAndSendToUser(messagingUser, QUEUE_ERRORS, error);
    }
}
