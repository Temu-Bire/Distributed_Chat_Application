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

    /** Message body is blank or too long. */
    public static final String INVALID_MESSAGE = "INVALID_MESSAGE";

    /** The inbound frame was missing information the server needs. */
    public static final String INVALID_COMMAND = "INVALID_COMMAND";

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String DUPLICATE_USERNAME = "DUPLICATE_USERNAME";
    public static final String DUPLICATE_EMAIL = "DUPLICATE_EMAIL";
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";
    public static final String NOT_MEMBER = "NOT_MEMBER";
    public static final String ALREADY_FRIENDS = "ALREADY_FRIENDS";
    public static final String REQUEST_ALREADY_EXISTS = "REQUEST_ALREADY_EXISTS";
    public static final String REQUEST_NOT_FOUND = "REQUEST_NOT_FOUND";
    public static final String CANNOT_FRIEND_SELF = "CANNOT_FRIEND_SELF";

    private final String code;

    public ChatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
