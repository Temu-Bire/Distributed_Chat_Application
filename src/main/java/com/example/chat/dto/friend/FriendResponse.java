package com.example.chat.dto.friend;

import java.time.Instant;
import java.util.UUID;

public record FriendResponse(
    UUID id,
    String username,
    String displayName,
    String avatarUrl,
    String bio,
    boolean online,
    Instant friendshipCreatedAt
) {}
