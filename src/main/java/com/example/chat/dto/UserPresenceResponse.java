package com.example.chat.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Outbound payload published on {@code /topic/users} whenever presence changes.
 *
 * <p>One message carries both the event and the resulting full user list, so a
 * client never has to reconstruct state from a stream of deltas and cannot be
 * left showing a stale list if a single frame is lost.</p>
 *
 * <pre>
 * {
 *   "event":     "JOIN" | "LEAVE" | "USERS",
 *   "username":  "alice" | null,      // who caused the change; null for USERS
 *   "users":     ["alice", "bob"],    // complete, sorted snapshot
 *   "timestamp": "2026-10-19T10:15:30.123Z"
 * }
 * </pre>
 */
public record UserPresenceResponse(Event event, String username, List<String> users, Instant timestamp) {

    public enum Event {
        /** {@code username} just connected. */
        JOIN,
        /** {@code username} just disconnected. */
        LEAVE,
        /** Snapshot sent in reply to a presence request; no single cause. */
        USERS
    }

    public UserPresenceResponse {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        users = List.copyOf(users);
    }

    public static UserPresenceResponse joined(String username, List<String> users, Instant timestamp) {
        return new UserPresenceResponse(Event.JOIN, username, users, timestamp);
    }

    public static UserPresenceResponse left(String username, List<String> users, Instant timestamp) {
        return new UserPresenceResponse(Event.LEAVE, username, users, timestamp);
    }

    public static UserPresenceResponse snapshot(List<String> users, Instant timestamp) {
        return new UserPresenceResponse(Event.USERS, null, users, timestamp);
    }
}
