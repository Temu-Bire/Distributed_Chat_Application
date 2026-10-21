package com.example.chat.security;

import com.example.chat.application.MessageRouter;
import com.example.chat.config.AnonymousHandshakeHandler;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.UserRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

/**
 * Authorizes STOMP SUBSCRIBE frames before they reach the broker.
 *
 * <p>Without this guard, any connection - including an anonymous one - could
 * subscribe to {@code /topic/conversations/{id}} for an id it guesses and
 * eavesdrop on every future message of that conversation, because the simple
 * broker does not check membership on its own.</p>
 *
 * <p>Rules applied here, kept intentionally small so the business services
 * stay the real authority:</p>
 * <ul>
 *   <li>{@code /topic/conversations/**} requires an authenticated session
 *       whose user is a member of that conversation.</li>
 *   <li>{@code /topic/users} requires an authenticated session.</li>
 *   <li>{@code /user/queue/**} is already scoped by Spring to the owning
 *       connection, so anything is allowed.</li>
 *   <li>Everything else passes through unchanged.</li>
 * </ul>
 */
@Component
public class StompSubscriptionGuard implements ChannelInterceptor {

    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;

    public StompSubscriptionGuard(UserRepository userRepository,
                                  ConversationMemberRepository memberRepository) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        String destination = resolveDestination(accessor.getDestination());
        if (destination == null) {
            return message;
        }

        if (destination.startsWith(MessageRouter.TOPIC_CONVERSATIONS + "/")) {
            UUID conversationId = parseConversationId(destination);
            requireMember(authenticatedUsername(accessor), conversationId);
        } else if (destination.equals(MessageRouter.TOPIC_USERS)) {
            authenticatedUsername(accessor);
        }
        return message;
    }

    /**
     * The messaging Principal for STOMP sessions is {@code SecurityUserPrincipal} or an
     * anonymous fallback created in {@code ChatSessionManager}; its name reflects the session's authenticated username.
     * This reads the user name the handshake stored on the frame, not the STOMP "login" header.
     */
    private String authenticatedUsername(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user == null || user.getName() == null || user.getName().startsWith(AnonymousHandshakeHandler.ANONYMOUS_PREFIX)) {
            throw new AccessDeniedException("Sign-in required for this subscription");
        }
        return user.getName();
    }

    private void requireMember(String username, UUID conversationId) {
        UUID userId = userRepository.findByUsernameIgnoreCase(username)
                .map(user -> user.getId())
                .orElseThrow(() -> new AccessDeniedException("Unknown account"));
        if (!memberRepository.existsByConversationIdAndUserId(conversationId, userId)) {
            throw new AccessDeniedException("Not a member of this conversation");
        }
    }

    private static UUID parseConversationId(String destination) {
        String tail = destination.substring(MessageRouter.TOPIC_CONVERSATIONS.length() + 1);
        int slash = tail.indexOf('/');
        String raw = slash >= 0 ? tail.substring(0, slash) : tail;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new AccessDeniedException("Unknown conversation");
        }
    }

    /**
     * The accessor's destination is a {@code String} for native STOMP but a
     * {@code SimpDestinationMessage} value for other transports; normalise both.
     */
    private static String resolveDestination(Object destination) {
        if (destination == null) {
            return null;
        }
        if (destination instanceof URI uri) {
            return uri.getPath();
        }
        return destination.toString();
    }
}
