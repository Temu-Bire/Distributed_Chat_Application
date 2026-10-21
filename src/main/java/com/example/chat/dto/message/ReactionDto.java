package com.example.chat.dto.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReactionDto(
    @NotBlank(message = "Emoji reaction is required")
    @Size(max = 16, message = "Reaction must be a short emoji")
    String emoji
) {}
