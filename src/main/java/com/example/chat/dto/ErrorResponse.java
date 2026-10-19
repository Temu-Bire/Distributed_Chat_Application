package com.example.chat.dto;

import java.time.Instant;

/**
 * Outbound failure payload.
 *
 * <p>Sent to {@code /user/queue/errors} so it reaches only the connection that
 * caused the problem, and returned as JSON with HTTP 400 by the REST exception
 * handler. {@code message} is always a safe, user-facing sentence: internal
 * detail and private message bodies are never included.</p>
 *
 * @param code      stable machine-readable identifier, e.g. {@code USERNAME_TAKEN}
 * @param message   human-readable explanation
 * @param timestamp when the failure was produced
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now());
    }
}
