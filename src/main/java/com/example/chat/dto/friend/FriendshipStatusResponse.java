package com.example.chat.dto.friend;

import java.util.UUID;

public record FriendshipStatusResponse(
    String status,
    UUID requestId
) {}
