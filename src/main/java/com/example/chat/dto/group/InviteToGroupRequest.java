package com.example.chat.dto.group;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record InviteToGroupRequest(
    @NotEmpty(message = "At least one user must be invited")
    List<UUID> userIds
) {}
