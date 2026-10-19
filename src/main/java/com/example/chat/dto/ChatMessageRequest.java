package com.example.chat.dto;

import com.example.chat.domain.ChatMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code /app/chat.send} (public message).
 *
 * <p>Bean Validation rejects blank or oversized bodies before the message
 * reaches the application layer; {@code ChatService} re-checks the length so
 * the rule still holds if this endpoint is ever called from another adapter.</p>
 */
public record ChatMessageRequest(

        @NotBlank(message = "Message content must not be blank")
        @Size(max = ChatMessage.MAX_CONTENT_LENGTH,
                message = "Messages must be at most " + ChatMessage.MAX_CONTENT_LENGTH + " characters")
        String content) {
}
