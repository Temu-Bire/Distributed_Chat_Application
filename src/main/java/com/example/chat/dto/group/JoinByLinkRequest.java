package com.example.chat.dto.group;

import jakarta.validation.constraints.NotBlank;

public record JoinByLinkRequest(
    @NotBlank(message = "An invite link token is required")
    String token
) {}
