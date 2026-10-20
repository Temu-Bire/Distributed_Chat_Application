package com.example.chat.dto.friend;

import java.util.UUID;

public record SendFriendRequest(
    String username,
    UUID userId
) {}
