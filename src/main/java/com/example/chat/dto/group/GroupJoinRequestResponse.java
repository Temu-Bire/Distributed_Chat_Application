package com.example.chat.dto.group;

import java.time.Instant;
import java.util.UUID;

public record GroupJoinRequestResponse(
    UUID id,
    UUID groupId,
    String groupName,
    String groupAvatar,
    UUID userId,
    String username,
    String displayName,
    String userAvatar,
    String status,
    Instant createdAt
) {}
