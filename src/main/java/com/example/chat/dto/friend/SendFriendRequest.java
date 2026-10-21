package com.example.chat.dto.friend;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendFriendRequest(
    String username,
    UUID userId,
    @Size(max = 280, message = "Request message must be at most 280 characters")
    String message
) {
    public SendFriendRequest(String username, UUID userId) {
        this(username, userId, null);
    }
}
