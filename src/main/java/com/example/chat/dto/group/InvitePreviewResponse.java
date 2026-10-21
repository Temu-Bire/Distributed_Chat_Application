package com.example.chat.dto.group;

import java.util.UUID;

/**
 * What a visitor sees before committing to a join. {@code state} is one of
 * {@code JOINABLE}, {@code ALREADY_MEMBER}, {@code BLOCKED} or
 * {@code INVALID}; the last covers every unusable token - unknown, revoked,
 * expired or used up - so an invite link never reports why it stopped
 * working to somebody who is not allowed in.
 */
public record InvitePreviewResponse(
    UUID groupId,
    String title,
    String description,
    String avatarUrl,
    int memberCount,
    String state
) {}
