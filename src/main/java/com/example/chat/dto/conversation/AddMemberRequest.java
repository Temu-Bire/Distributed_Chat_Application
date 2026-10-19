package com.example.chat.dto.conversation;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddMemberRequest(
    @NotNull(message = "User ID is required")
    UUID userId
) {}
