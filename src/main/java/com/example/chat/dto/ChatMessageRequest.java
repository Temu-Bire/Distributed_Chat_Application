package com.example.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound payload for sending a message into a persisted conversation.
 *
 * <p>Bean Validation rejects blank or oversized bodies before the message
 * reaches the application layer.</p>
 */
public record ChatMessageRequest(

        @NotBlank(message = "Message content must not be blank")
        @Size(max = ChatMessageRequest.MAX_CONTENT_LENGTH,
                message = "Messages must be at most " + ChatMessageRequest.MAX_CONTENT_LENGTH + " characters")
        String content,

        UUID replyToId,

        @Size(max = 64, message = "Client message id must be at most 64 characters")
        String clientMessageId) {

    /** Maximum body length, mirrored by the persisted message service. */
    public static final int MAX_CONTENT_LENGTH = 2000;

    public ChatMessageRequest(String content) {
        this(content, null, null);
    }
}
