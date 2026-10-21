package com.example.chat.dto.settings;

import com.example.chat.domain.entity.UserSettingsEntity;

public record UserSettingsDto(
    String theme,
    boolean notificationsEnabled,
    boolean soundEnabled,
    boolean onlineVisibility,
    boolean notifyMessages,
    boolean notifyMentions,
    boolean notifyFriendRequests,
    boolean notifyGroupActivity
) {
    public static UserSettingsDto from(UserSettingsEntity entity) {
        return new UserSettingsDto(
            entity.getTheme(),
            entity.isNotificationsEnabled(),
            entity.isSoundEnabled(),
            entity.isOnlineVisibility(),
            entity.isNotifyMessages(),
            entity.isNotifyMentions(),
            entity.isNotifyFriendRequests(),
            entity.isNotifyGroupActivity()
        );
    }
}
