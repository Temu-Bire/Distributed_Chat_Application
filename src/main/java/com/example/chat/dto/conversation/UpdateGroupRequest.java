package com.example.chat.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateGroupRequest(
    @NotBlank(message = "Group title is required")
    @Size(min = 1, max = 100, message = "Group title must be between 1 and 100 characters")
    String title,

    @Size(max = 500, message = "Description must be at most 500 characters")
    String description,

    @Size(max = 255, message = "Avatar URL must be at most 255 characters")
    String avatarUrl,

    Boolean isPrivate
) {
    public UpdateGroupRequest(String title, String description, String avatarUrl) {
        this(title, description, avatarUrl, null);
    }
}
