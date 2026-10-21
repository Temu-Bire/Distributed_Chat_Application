package com.example.chat.domain.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "user_settings")
public class UserSettingsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, length = 10)
    private String theme = "light";

    @Column(nullable = false)
    private boolean notificationsEnabled = true;

    @Column(nullable = false)
    private boolean soundEnabled = true;

    @Column(nullable = false)
    private boolean onlineVisibility = true;

    @Column(name = "notify_messages", nullable = false)
    private boolean notifyMessages = true;

    @Column(name = "notify_mentions", nullable = false)
    private boolean notifyMentions = true;

    @Column(name = "notify_friend_requests", nullable = false)
    private boolean notifyFriendRequests = true;

    @Column(name = "notify_group_activity", nullable = false)
    private boolean notifyGroupActivity = true;

    public UserSettingsEntity() {}

    public UserSettingsEntity(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }

    public boolean isSoundEnabled() { return soundEnabled; }
    public void setSoundEnabled(boolean soundEnabled) { this.soundEnabled = soundEnabled; }

    public boolean isOnlineVisibility() { return onlineVisibility; }
    public void setOnlineVisibility(boolean onlineVisibility) { this.onlineVisibility = onlineVisibility; }

    public boolean isNotifyMessages() { return notifyMessages; }
    public void setNotifyMessages(boolean notifyMessages) { this.notifyMessages = notifyMessages; }

    public boolean isNotifyMentions() { return notifyMentions; }
    public void setNotifyMentions(boolean notifyMentions) { this.notifyMentions = notifyMentions; }

    public boolean isNotifyFriendRequests() { return notifyFriendRequests; }
    public void setNotifyFriendRequests(boolean notifyFriendRequests) { this.notifyFriendRequests = notifyFriendRequests; }

    public boolean isNotifyGroupActivity() { return notifyGroupActivity; }
    public void setNotifyGroupActivity(boolean notifyGroupActivity) { this.notifyGroupActivity = notifyGroupActivity; }
}
