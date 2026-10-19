package com.example.chat.dto.auth;

import com.example.chat.domain.entity.UserEntity;
import java.util.UUID;

public record UserSearchResult(
    UUID id,
    String username,
    String displayName,
    String avatarUrl,
    boolean online
) {
    public static UserSearchResult from(UserEntity user, boolean online) {
        return new UserSearchResult(
            user.getId(),
            user.getUsername(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            online
        );
    }
}
