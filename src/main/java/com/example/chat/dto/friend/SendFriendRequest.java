package com.example.chat.dto.friend;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record SendFriendRequest(
    String username,
    UUID userId
) {}
