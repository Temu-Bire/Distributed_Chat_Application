package com.example.chat.dto.group;

import java.time.Instant;
import java.util.UUID;

public record GroupInvitationResponse(
    UUID id,
    UUID groupId,
    String groupName,
    String groupAvatar,
    UUID inviterId,
    String inviterUsername,
    String inviterDisplayName,
    String inviterAvatar,
    UUID invitedUserId,
    String status,
    Instant createdAt
) {}
