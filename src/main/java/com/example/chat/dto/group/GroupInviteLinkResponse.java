package com.example.chat.dto.group;

import java.time.Instant;
import java.util.UUID;

/**
 * One invite link, with everything the settings dialog needs to show its
 * remaining lifetime and budget. {@code revoked} links are only ever returned
 * to their creators' group admins, never to a joiner.
 */
public record GroupInviteLinkResponse(
    UUID id,
    UUID groupId,
    String groupTitle,
    String token,
    String joinUrl,
    Instant createdAt,
    Instant expiresAt,
    Integer maxUses,
    int usedCount,
    boolean revoked,
    boolean active
) {}
