package com.example.chat.dto;

import com.example.chat.domain.ChatUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Inbound payload for {@code /app/chat.join}.
 *
 * <p>Carries only the requested display name. There is deliberately no
 * {@code sessionId} or {@code sender} field: the server derives both from the
 * connection, so a client cannot claim to be someone else.</p>
 */
public record JoinRequest(

        @NotBlank(message = "Username must not be blank")
        @Size(max = ChatUser.MAX_USERNAME_LENGTH,
                message = "Username must be at most " + ChatUser.MAX_USERNAME_LENGTH + " characters")
        String username) {
}
