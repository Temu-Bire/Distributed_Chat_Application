package com.example.chat.application;

import com.example.chat.dto.ErrorResponse;
import com.example.chat.dto.UserPresenceResponse;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import org.springframework.beans.factory.ObjectProvider;
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
 * /topic/notifications/{userId}      NotificationDto      live notifications, one user
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
    public static final String TOPIC_NOTIFICATIONS = "/topic/notifications";

    public static String conversationTopic(UUID conversationId) {
        return TOPIC_CONVERSATIONS + "/" + conversationId;
    }

    /**
     * The one topic a user's own live notifications arrive on. It is a topic
     * rather than {@code /user/queue/...} so {@code DistributedMessageRelay}
     * can carry it between backend instances; ownership is enforced by
     * {@code StompSubscriptionGuard}, which only lets the matching session
     * subscribe.
     */
    public static String notificationTopic(UUID userId) {
        return TOPIC_NOTIFICATIONS + "/" + userId;
    }

    private final SimpMessagingTemplate messaging;
    private final ObjectProvider<DistributedMessageRelay> relayProvider;

    public MessageRouter(SimpMessagingTemplate messaging, ObjectProvider<DistributedMessageRelay> relayProvider) {
        this.messaging = messaging;
        this.relayProvider = relayProvider;
    }

    /** Publishes the user list and join/leave event to every subscriber. */
    public void broadcastPresence(UserPresenceResponse presence) {
        messaging.convertAndSend(TOPIC_USERS, presence);
        // The payload is a complete snapshot taken from the shared presence
        // store, so replaying it on another node is correct rather than a
        // partial delta from one node's point of view.
        try {
            DistributedMessageRelay relay = relayProvider != null ? relayProvider.getIfAvailable() : null;
            if (relay != null) {
                relay.publishCrossInstance(TOPIC_USERS, presence);
            }
        } catch (Exception e) {
            // A presence hint that fails to fan out is not worth failing a join over.
        }
    }

    /** Delivers a failure to exactly one connection. */
    public void sendError(String messagingUser, ErrorResponse error) {
        messaging.convertAndSendToUser(messagingUser, QUEUE_ERRORS, error);
    }
}
