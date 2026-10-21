package com.example.chat.dto.group;

import java.time.Instant;
import java.util.UUID;

public record GroupDiscoveryResponse(
    UUID id,
    String title,
    String description,
    String avatarUrl,
    int memberCount,
    String type,
    boolean isPrivate,
    String membershipStatus,
    Instant createdAt
) {}
