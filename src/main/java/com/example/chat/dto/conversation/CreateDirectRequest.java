package com.example.chat.dto.conversation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateDirectRequest(
    @NotNull(message = "Recipient user ID is required")
    UUID recipientId
) {}
