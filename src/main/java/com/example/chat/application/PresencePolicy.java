package com.example.chat.application;

import com.example.chat.domain.entity.UserEntity;
import com.example.chat.domain.entity.UserSettingsEntity;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single authority on presence as other accounts are allowed to see it.
 *
 * <p>{@link PresenceService} knows who is connected; this knows who may be seen
 * connected. Reading the live registry directly would make the "share my online
 * status" setting decorative, so every response carrying an {@code online} flag
 * goes through here instead.</p>
 */
@Service
public class PresencePolicy {

    private final PresenceService presenceService;
    private final UserSettingsRepository settingsRepository;

    public PresencePolicy(PresenceService presenceService, UserSettingsRepository settingsRepository) {
        this.presenceService = presenceService;
        this.settingsRepository = settingsRepository;
    }

    /** True when the account is connected and has not hidden its status. */
    @Transactional(readOnly = true)
    public boolean isShownOnline(UserEntity user) {
        return presenceService.isOnline(user.getUsername()) && sharesPresence(user.getId());
    }

    /** False only when the account opted out; a missing settings row means the default, share. */
    @Transactional(readOnly = true)
    public boolean sharesPresence(UUID userId) {
        return settingsRepository.findByUserId(userId)
            .map(UserSettingsEntity::isOnlineVisibility)
            .orElse(true);
    }
}
