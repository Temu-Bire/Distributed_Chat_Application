package com.example.chat.dto.message;

import com.example.chat.domain.entity.MessageEntity;
import java.time.Instant;
import java.util.UUID;

public record PersistentMessageResponse(
    UUID id,
    UUID conversationId,
    UUID senderId,
    String senderUsername,
    String content,
    boolean edited,
    boolean deleted,
    Instant createdAt,
    Instant editedAt
) {
    public static PersistentMessageResponse from(MessageEntity msg) {
        return new PersistentMessageResponse(
            msg.getId(),
            msg.getConversationId(),
            msg.getSenderId(),
            msg.getSenderUsername(),
            msg.getContent(),
            msg.isEdited(),
            msg.isDeleted(),
            msg.getCreatedAt(),
            msg.getEditedAt()
        );
    }
}
