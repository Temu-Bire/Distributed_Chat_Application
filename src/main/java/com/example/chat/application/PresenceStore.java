package com.example.chat.application;

import com.example.chat.domain.ChatUser;

import java.util.List;
import java.util.Optional;

/**
 * Storage contract for who is currently connected.
 *
 * <p>This is the seam for horizontal scaling: the MVP uses
 * {@code InMemoryPresenceStore}, and a multi-instance deployment swaps in a
 * Redis-backed implementation without touching {@link PresenceService} or any
 * other class.</p>
 *
 * <p>Implementations must be safe for concurrent use: two clients may try to
 * claim the same display name at the same time, and the same connection may be
 * registered and removed while other threads are reading the list.</p>
 */
public interface PresenceStore {

    /**
     * Registers a connection under its display name.
     *
     * <p>Any previous registration for the same {@code sessionId} is replaced,
     * so a connection that re-joins does not leak its earlier entry.</p>
     *
     * @return {@code true} if registered, {@code false} if the display name is
     *         already held by a different connection
     */
    boolean register(ChatUser user);

    /**
     * Removes the user for a connection.
     *
     * @return the removed user, or empty if that session was never registered
     */
    Optional<ChatUser> unregister(String sessionId);

    /** Looks up the user behind a connection. */
    Optional<ChatUser> findBySessionId(String sessionId);

    /** Looks up a connected user by display name, case-insensitively. */
    Optional<ChatUser> findByUsername(String username);

    /** All connected users, sorted case-insensitively by display name. */
    List<ChatUser> findAll();
}
