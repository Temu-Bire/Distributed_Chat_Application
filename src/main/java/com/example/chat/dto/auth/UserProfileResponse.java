package com.example.chat.dto.auth;

import com.example.chat.domain.entity.UserEntity;
import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
    UUID id,
    String username,
    String email,
    String displayName,
    String avatarUrl,
    String bio,
    Instant createdAt
) {
    public static UserProfileResponse from(UserEntity user) {
        return new UserProfileResponse(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            user.getBio(),
            user.getCreatedAt()
        );
    }
}
