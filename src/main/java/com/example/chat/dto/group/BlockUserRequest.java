package com.example.chat.dto.group;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record BlockUserRequest(
    @NotNull(message = "userId is required")
    UUID userId
) {}
