package com.example.chat.dto.message;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ForwardMessageDto(
    @NotNull(message = "messageId is required")
    UUID messageId,

    @Size(max = 64, message = "clientMessageId must be at most 64 characters")
    String clientMessageId
) {
}
