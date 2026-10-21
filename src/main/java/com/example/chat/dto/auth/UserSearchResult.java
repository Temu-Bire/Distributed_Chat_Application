package com.example.chat.dto.auth;

import com.example.chat.domain.entity.UserEntity;
import java.util.UUID;

public record UserSearchResult(
    UUID id,
    String username,
    String displayName,
    String avatarUrl,
    boolean online,
    String friendshipStatus
) {
    public static UserSearchResult from(UserEntity user, boolean online) {
        return from(user, online, "NONE");
    }

    public static UserSearchResult from(UserEntity user, boolean online, String friendshipStatus) {
        return new UserSearchResult(
            user.getId(),
            user.getUsername(),
            user.getDisplayName(),
            user.getAvatarUrl(),
            online,
            friendshipStatus == null ? "NONE" : friendshipStatus
        );
    }

    public UserSearchResult withFriendshipStatus(String status) {
        return new UserSearchResult(id, username, displayName, avatarUrl, online, status);
    }
}
