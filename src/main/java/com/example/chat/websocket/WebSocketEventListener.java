package com.example.chat.websocket;

import com.example.chat.application.ChatService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Turns a transport-level disconnect into a presence change.
 *
 * <p>Kept apart from the controller because the disconnect path has a different
 * trigger (the container reacting to a closed socket, not a client frame) and
 * is worth reading on its own. {@code ChatService#leave} is idempotent, so a
 * connection that never joined, or one already cleaned up, causes no harm.</p>
 *
 * <p>No lock is held while this runs and the resulting publish is
 * non-blocking, so cleaning up a dead connection cannot hold up a live one.</p>
 */
@Component
public class WebSocketEventListener {

    private final ChatService chatService;

    public WebSocketEventListener(ChatService chatService) {
        this.chatService = chatService;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        chatService.leave(event.getSessionId());
    }
}
