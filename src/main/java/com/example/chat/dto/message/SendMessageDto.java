package com.example.chat.dto.message;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendMessageDto(
    @Size(max = 2000, message = "Message must be at most 2000 characters")
    String content,
    UUID replyToId,
    @Size(max = 64, message = "Client message id must be at most 64 characters")
    String clientMessageId,
    AttachmentRefDto attachment
) {
    public SendMessageDto(String content) {
        this(content, null, null, null);
    }
}
