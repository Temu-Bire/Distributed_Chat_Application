package com.example.chat.domain;

/**
 * A user who currently holds a live connection to the chat.
 *
 * <p>This is a domain value object: it holds no Spring types. The two
 * connection handles are kept as opaque strings so the domain stays
 * independent of the transport that produced them.</p>
 *
 * @param username      the display name chosen at join time; unique among
 *                      connected users (case-insensitively)
 * @param sessionId     the STOMP session id Spring assigned to the connection.
 *                      Used to look the user up on the message path and to
 *                      clean up on disconnect.
 * @param messagingUser the name Spring uses to resolve this connection's
 *                      {@code /user/**} destinations. Private messages are
 *                      addressed with it, so delivery is scoped to one
 *                      connection rather than to a display name.
 */
public record ChatUser(String username, String sessionId, String messagingUser) {

    /** Maximum display-name length, mirrored by {@code @Size} on the join DTO. */
    public static final int MAX_USERNAME_LENGTH = 20;

    public ChatUser {
        username = requireText(username, "username");
        sessionId = requireText(sessionId, "sessionId");
        messagingUser = requireText(messagingUser, "messagingUser");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
