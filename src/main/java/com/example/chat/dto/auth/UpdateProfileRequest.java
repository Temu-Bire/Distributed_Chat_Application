package com.example.chat.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @Size(min = 1, max = 50, message = "Display name must be between 1 and 50 characters")
    String displayName,

    @Size(max = 255, message = "Bio must be at most 255 characters")
    String bio,

    @Size(max = 255, message = "Avatar URL must be at most 255 characters")
    String avatarUrl
) {}
