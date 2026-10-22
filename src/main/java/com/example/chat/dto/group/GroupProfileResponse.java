package com.example.chat.dto.group;

import com.example.chat.dto.conversation.ConversationMemberResponse;
import com.example.chat.domain.entity.MemberRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The group card: what a group looks like from the outside, and everything it
 * looks like from the inside.
 *
 * <p>{@link #members()} is populated only for members. A public group's name,
 * description and headcount are advertised in discovery already, so revealing
 * those costs nothing - but its roster is the list of people who are in it, and
 * that belongs to the people who joined.</p>
 *
 * @param membershipStatus MEMBER, PENDING, BLOCKED or NONE
 * @param myRole           the caller's role, or null when they are not a member
 * @param members          the roster, empty for anybody outside the group
 */
public record GroupProfileResponse(
    UUID id,
    String title,
    String description,
    String avatarUrl,
    boolean isPrivate,
    Instant createdAt,
    int memberCount,
    String membershipStatus,
    MemberRole myRole,
    List<ConversationMemberResponse> members
) {}
