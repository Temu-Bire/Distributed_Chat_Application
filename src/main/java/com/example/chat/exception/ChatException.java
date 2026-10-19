package com.example.chat.exception;

/**
 * Expected, user-correctable failure in a chat use case.
 *
 * <p>Carries a stable {@link #code()} so the browser can react to a specific
 * condition (for example {@link #USERNAME_TAKEN} after a reconnect) instead of
 * matching on a human-readable sentence.</p>
 */
public class ChatException extends RuntimeException {

    /** The requested username is blank, too long, or contains control characters. */
    public static final String INVALID_USERNAME = "INVALID_USERNAME";

    /** The requested username is already held by another live connection. */
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";

    /** The connection has not completed the join step yet. */
    public static final String NOT_JOINED = "NOT_JOINED";

    /** Message body is blank or longer than {@code ChatMessage.MAX_CONTENT_LENGTH}. */
    public static final String INVALID_MESSAGE = "INVALID_MESSAGE";

    /** The private-message target is blank, is the sender, or is not connected. */
    public static final String RECIPIENT_NOT_FOUND = "RECIPIENT_NOT_FOUND";

    /** The inbound frame was missing information the server needs. */
    public static final String INVALID_COMMAND = "INVALID_COMMAND";

    private final String code;

    public ChatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
