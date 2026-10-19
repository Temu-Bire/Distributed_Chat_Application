package com.example.chat.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Gives every WebSocket connection a server-generated {@link Principal}.
 *
 * <h3>Why this is needed</h3>
 * <p>Spring resolves {@code /user/**} destinations through its
 * {@code SimpUserRegistry}, and a session is only registered there when the
 * handshake produced a principal. The MVP has no login, so without this class
 * the registry would be empty and
 * {@code SimpMessagingTemplate.convertAndSendToUser(...)} would resolve to
 * <em>nobody</em> — private messages would silently vanish. Assigning an
 * opaque identity per connection also scopes delivery to exactly one
 * connection.</p>
 *
 * <h3>Authentication seam</h3>
 * <p>If the request already carries an authenticated principal, it is used
 * unchanged. Adding real authentication therefore means populating that
 * principal (for example from a token during the handshake); this class and
 * everything downstream keep working.</p>
 *
 * <p>The generated name is intentionally meaningless: it is a routing key,
 * never shown to a user and never trusted as a display name.</p>
 */
public class AnonymousHandshakeHandler extends DefaultHandshakeHandler {

    private static final String ANONYMOUS_PREFIX = "anon-";

    @Override
    protected Principal determineUser(ServerHttpRequest request, WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        Principal authenticated = request.getPrincipal();
        if (authenticated != null) {
            return authenticated;
        }
        return new AnonymousPrincipal(ANONYMOUS_PREFIX + UUID.randomUUID());
    }

    private record AnonymousPrincipal(String name) implements Principal {

        @Override
        public String getName() {
            return name;
        }
    }
}
