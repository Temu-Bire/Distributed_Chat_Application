package com.example.chat.dto.message;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The messages a caller wants copied into this conversation, with an optional
 * batch id that makes the whole request replayable.
 */
public record BulkForwardMessageDto(
    @NotEmpty(message = "messageIds is required")
    @Size(max = 100, message = "At most 100 messages can be forwarded at once")
    List<UUID> messageIds,

    @Size(max = 64, message = "clientMessageId must be at most 64 characters")
    String clientMessageId
) {
}
