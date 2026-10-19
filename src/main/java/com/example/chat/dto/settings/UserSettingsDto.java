package com.example.chat.dto.settings;

import com.example.chat.domain.entity.UserSettingsEntity;

public record UserSettingsDto(
    String theme,
    boolean notificationsEnabled,
    boolean soundEnabled,
    boolean onlineVisibility
) {
    public static UserSettingsDto from(UserSettingsEntity entity) {
        return new UserSettingsDto(
            entity.getTheme(),
            entity.isNotificationsEnabled(),
            entity.isSoundEnabled(),
            entity.isOnlineVisibility()
        );
    }
}
