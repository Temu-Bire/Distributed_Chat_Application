package com.example.chat.dto.conversation;

import com.example.chat.domain.entity.MemberRole;
import java.time.Instant;
import java.util.UUID;

public record ConversationMemberResponse(
    UUID userId,
    String username,
    String displayName,
    String avatarUrl,
    MemberRole role,
    Instant joinedAt,
    boolean online
) {}
