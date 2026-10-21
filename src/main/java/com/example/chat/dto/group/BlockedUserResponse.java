package com.example.chat.dto.group;

import java.time.Instant;
import java.util.UUID;

public record BlockedUserResponse(
    UUID userId,
    String username,
    String displayName,
    String avatarUrl,
    Instant blockedAt
) {}
