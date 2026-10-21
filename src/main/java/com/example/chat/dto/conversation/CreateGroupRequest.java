package com.example.chat.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateGroupRequest(
    @NotBlank(message = "Group title is required")
    @Size(min = 1, max = 100, message = "Group title must be between 1 and 100 characters")
    String title,

    @Size(max = 500, message = "Description must be at most 500 characters")
    String description,

    @Size(max = 255, message = "Avatar URL must be at most 255 characters")
    String avatarUrl,

    List<UUID> memberIds,

    Boolean isPrivate
) {
    public CreateGroupRequest(String title, String description, String avatarUrl, List<UUID> memberIds) {
        this(title, description, avatarUrl, memberIds, null);
    }
}
