package com.example.chat.application;

import com.example.chat.domain.ChatUser;
import com.example.chat.exception.ChatException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Application service that owns the rules of who is online.
 *
 * <p>Everything that changes the connected-user set goes through here so that
 * the controller stays free of presence logic and the store implementation
 * stays swappable. The service is stateless; all state lives in the injected
 * {@link PresenceStore}.</p>
 */
@Service
public class PresenceService {

    private final PresenceStore store;

    public PresenceService(PresenceStore store) {
        this.store = store;
    }

    /**
     * Registers a connection under {@code username}.
     *
     * @param sessionId     the STOMP session id for the connection
     * @param messagingUser the name Spring resolves this connection's
     *                      {@code /user/**} destinations with
     * @param username      the validated display name
     * @throws ChatException if another connection already holds the name
     */
    public ChatUser connect(String sessionId, String messagingUser, String username) {
        ChatUser user = new ChatUser(username, sessionId, messagingUser);
        if (!store.register(user)) {
            throw new ChatException(ChatException.USERNAME_TAKEN,
                    "The username '" + username + "' is already in use.");
        }
        return user;
    }

    /**
     * Drops the user for a closed connection.
     *
     * @return the user that was removed, or empty if the connection never joined
     */
    public Optional<ChatUser> disconnect(String sessionId) {
        return store.unregister(sessionId);
    }

    /**
     * Resolves the connected user behind a session.
     *
     * <p>This is how every message gets its sender: the display name comes from
     * the server-side session, never from the client payload.</p>
     *
     * @throws ChatException if the connection has not joined yet
     */
    public ChatUser requireUser(String sessionId) {
        return store.findBySessionId(sessionId)
                .orElseThrow(() -> new ChatException(ChatException.NOT_JOINED,
                        "Join the chat before sending messages."));
    }

    /** Finds a connected user by display name, case-insensitively. */
    public Optional<ChatUser> findByUsername(String username) {
        return store.findByUsername(username);
    }

    /** Returns true if the user with the given username is currently online. */
    public boolean isOnline(String username) {
        return findByUsername(username).isPresent();
    }

    /** Display names of everyone currently connected, sorted. */
    public List<String> onlineUsernames() {
        return store.findAll().stream().map(ChatUser::username).toList();
    }

    /** Number of live connections. */
    public int onlineCount() {
        return store.findAll().size();
    }
}
