package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.domain.entity.UserSettingsEntity;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.settings.UserSettingsDto;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserSettingsRepository settingsRepository;
    private final AuthService authService;

    public SettingsController(UserSettingsRepository settingsRepository, AuthService authService) {
        this.settingsRepository = settingsRepository;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<UserSettingsDto> getSettings(Principal principal) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        UserSettingsEntity settings = settingsRepository.findByUserId(user.id())
            .orElseGet(() -> settingsRepository.save(new UserSettingsEntity(user.id())));
        return ResponseEntity.ok(UserSettingsDto.from(settings));
    }

    @PutMapping
    public ResponseEntity<UserSettingsDto> updateSettings(Principal principal, @RequestBody UserSettingsDto dto) {
        UserProfileResponse user = authService.getCurrentUser(principal);
        UserSettingsEntity settings = settingsRepository.findByUserId(user.id())
            .orElseGet(() -> new UserSettingsEntity(user.id()));

        if (dto.theme() != null) settings.setTheme(dto.theme());
        settings.setNotificationsEnabled(dto.notificationsEnabled());
        settings.setSoundEnabled(dto.soundEnabled());
        settings.setOnlineVisibility(dto.onlineVisibility());
        settings.setNotifyMessages(dto.notifyMessages());
        settings.setNotifyMentions(dto.notifyMentions());
        settings.setNotifyFriendRequests(dto.notifyFriendRequests());
        settings.setNotifyGroupActivity(dto.notifyGroupActivity());
        settings = settingsRepository.save(settings);

        return ResponseEntity.ok(UserSettingsDto.from(settings));
    }
}
