package com.example.chat.dto.message;

import com.example.chat.domain.entity.MessageEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PersistentMessageResponse(
    UUID id,
    UUID conversationId,
    UUID senderId,
    String senderUsername,
    String content,
    boolean edited,
    boolean deleted,
    boolean pinned,
    int readByCount,
    boolean forwarded,
    UUID replyToId,
    String replyToContent,
    List<ReactionSummaryDto> reactions,
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
            msg.isPinned(),
            0,
            false,
            msg.getReplyToId(),
            null,
            List.of(),
            msg.getCreatedAt(),
            msg.getEditedAt()
        );
    }

    public PersistentMessageResponse withReplyPreview(String preview) {
        return new PersistentMessageResponse(
            id, conversationId, senderId, senderUsername, content,
            edited, deleted, pinned, readByCount, forwarded, replyToId, preview, reactions, createdAt, editedAt
        );
    }

    public PersistentMessageResponse withReactions(List<ReactionSummaryDto> newReactions) {
        return new PersistentMessageResponse(
            id, conversationId, senderId, senderUsername, content,
            edited, deleted, pinned, readByCount, forwarded, replyToId, replyToContent, newReactions, createdAt, editedAt
        );
    }
}
