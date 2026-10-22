package com.example.chat.dto.message;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The messages a caller wants deleted in one action. The batch is capped: a
 * selection cannot be larger than the history page the client was showing, and
 * the cap keeps one request from tombstoning a conversation the server has not
 * loaded.
 */
public record BulkDeleteMessageDto(
    @NotEmpty(message = "messageIds is required")
    @Size(max = 100, message = "At most 100 messages can be deleted at once")
    List<UUID> messageIds
) {
}
