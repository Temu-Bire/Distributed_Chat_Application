package com.example.chat.dto.friend;

import com.example.chat.domain.entity.FriendRequestStatus;
import java.time.Instant;
import java.util.UUID;

public record FriendRequestResponse(
    UUID id,
    UUID senderId,
    String senderUsername,
    String senderDisplayName,
    String senderAvatarUrl,
    UUID receiverId,
    String receiverUsername,
    String receiverDisplayName,
    String receiverAvatarUrl,
    FriendRequestStatus status,
    Instant createdAt
) {}
