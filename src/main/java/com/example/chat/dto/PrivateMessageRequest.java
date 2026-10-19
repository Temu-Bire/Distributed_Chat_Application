package com.example.chat.dto;

import com.example.chat.domain.ChatMessage;
import com.example.chat.domain.ChatUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code /app/chat.private}.
 *
 * <p>A separate record rather than an optional field on
 * {@link ChatMessageRequest}, because the target is mandatory here and
 * optional there — modelling that difference in the type keeps the validation
 * rules declarative instead of branching inside the service.</p>
 */
public record PrivateMessageRequest(

        @NotBlank(message = "Recipient must not be blank")
        @Size(max = ChatUser.MAX_USERNAME_LENGTH,
                message = "Recipient name must be at most " + ChatUser.MAX_USERNAME_LENGTH + " characters")
        String recipient,

        @NotBlank(message = "Message content must not be blank")
        @Size(max = ChatMessage.MAX_CONTENT_LENGTH,
                message = "Messages must be at most " + ChatMessage.MAX_CONTENT_LENGTH + " characters")
        String content) {
}
