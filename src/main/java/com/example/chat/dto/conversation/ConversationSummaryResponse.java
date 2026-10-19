package com.example.chat.dto.conversation;

import com.example.chat.domain.entity.ConversationType;
import java.time.Instant;
import java.util.UUID;

public record ConversationSummaryResponse(
    UUID id,
    ConversationType type,
    String title,
    String description,
    String avatarUrl,
    UUID createdBy,
    String lastMessageText,
    Instant lastMessageAt,
    String lastMessageSender,
    long unreadCount,
    boolean isGroup,
    String otherUsername,
    UUID otherUserId
) {}
