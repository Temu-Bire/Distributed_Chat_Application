package com.example.chat.exception;

import com.example.chat.application.MessageRouter;
import com.example.chat.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.security.Principal;
import java.util.Objects;

/**
 * One consistent failure path for every failure the client can trigger.
 *
 * <p>Exceptions thrown from a {@code @MessageMapping} handler are converted to
 * an {@link ErrorResponse} and pushed to the originating connection's
 * {@code /user/queue/errors}. Nothing is ever broadcast, so one user's mistake
 * cannot be seen by, or disruptive to, anyone else.</p>
 *
 * <p>The same {@link ChatException} is mapped to HTTP 400 for the REST side
 * (currently {@code /api/health}), so the error contract is identical whichever
 * adapter raised it.</p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final MessageRouter router;

    public GlobalExceptionHandler(MessageRouter router) {
        this.router = router;
    }

    /** Expected, user-correctable failures: keep the specific code and message. */
    @MessageExceptionHandler(ChatException.class)
    public void handleChatException(ChatException ex, SimpMessageHeaderAccessor headers) {
        log.debug("Rejected a chat command with code {}: {}", ex.code(), ex.getMessage());
        reply(headers, ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    /** Bean Validation rejected the payload before the service saw it. */
    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    public void handleInvalidPayload(MethodArgumentNotValidException ex, SimpMessageHeaderAccessor headers) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("The message was not valid.");
        reply(headers, ErrorResponse.of(ChatException.INVALID_MESSAGE, message));
    }

    /**
     * Anything else is a bug. The detail goes to the server log only; the client
     * gets a generic sentence so internal state is never disclosed.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleUnexpected(Exception ex, SimpMessageHeaderAccessor headers) {
        log.error("Unexpected failure while handling a STOMP frame", ex);
        reply(headers, ErrorResponse.of(INTERNAL_ERROR, "An unexpected server error occurred."));
    }

    /** REST equivalent, used by the HTTP adapters. */
    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ErrorResponse> handleRestChatException(ChatException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    private void reply(SimpMessageHeaderAccessor headers, ErrorResponse error) {
        // The principal is assigned by the handshake handler, so it is present
        // even before the connection has joined — the client still learns why
        // its join was refused.
        Principal user = headers.getUser();
        if (user == null) {
            log.warn("Dropping error '{}' because the STOMP session has no user", error.code());
            return;
        }
        router.sendError(user.getName(), error);
    }
}
