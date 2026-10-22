package com.example.chat.dto.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * What one account is allowed to see about another.
 *
 * <p>Narrower than {@link UserProfileResponse} on purpose: that record carries
 * the email address and is only ever returned for the caller themselves. This
 * one is returned for arbitrary users, so it holds nothing but what a profile
 * card should show.</p>
 *
 * @param online            only ever true while the target shares their status
 * @param shareOnlineStatus the target's own {@code onlineVisibility} choice, so
 *                          the client can say "hidden" instead of "offline"
 * @param friendshipStatus  SELF, FRIENDS, PENDING_SENT, PENDING_RECEIVED or NONE
 * @param mutualFriends     accounts both of them have added
 * @param sharedGroups      groups both of them belong to
 */
public record PublicProfileResponse(
    UUID id,
    String username,
    String displayName,
    String avatarUrl,
    String bio,
    Instant joinedAt,
    boolean online,
    boolean shareOnlineStatus,
    String friendshipStatus,
    int mutualFriends,
    int sharedGroups
) {}
