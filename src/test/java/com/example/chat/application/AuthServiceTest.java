package com.example.chat.application;

import com.example.chat.domain.entity.UserEntity;
import com.example.chat.dto.auth.*;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.UserRepository;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSettingsRepository settingsRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("user registration creates user and settings")
    void registerSuccess() {
        RegisterRequest req = new RegisterRequest("dave", "dave@example.com", "secret123", "Dave K");
        UserProfileResponse res = authService.register(req, null);

        assertThat(res.id()).isNotNull();
        assertThat(res.username()).isEqualTo("dave");
        assertThat(res.email()).isEqualTo("dave@example.com");
        assertThat(res.displayName()).isEqualTo("Dave K");

        UserEntity entity = userRepository.findById(res.id()).orElseThrow();
        assertThat(passwordEncoder.matches("secret123", entity.getPassword())).isTrue();
        assertThat(settingsRepository.findByUserId(res.id())).isPresent();
    }

    @Test
    @DisplayName("registration rejects duplicate username regardless of letter case")
    void registerDuplicateUsername() {
        authService.register(new RegisterRequest("eve", "eve@example.com", "secret123", "Eve"), null);

        RegisterRequest duplicate = new RegisterRequest("EVE", "eve2@example.com", "secret123", "Eve 2");
        assertThatThrownBy(() -> authService.register(duplicate, null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.DUPLICATE_USERNAME);
    }

    @Test
    @DisplayName("registration rejects duplicate email regardless of letter case")
    void registerDuplicateEmail() {
        authService.register(new RegisterRequest("frank", "frank@example.com", "secret123", "Frank"), null);

        RegisterRequest duplicate = new RegisterRequest("frank2", "FRANK@example.com", "secret123", "Frank 2");
        assertThatThrownBy(() -> authService.register(duplicate, null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("login succeeds with correct email and password")
    void loginSuccess() {
        authService.register(new RegisterRequest("grace", "grace@example.com", "password456", "Grace"), null);

        UserProfileResponse loginRes = authService.login(new LoginRequest("grace@example.com", "password456"), null);
        assertThat(loginRes.username()).isEqualTo("grace");
    }

    @Test
    @DisplayName("login fails with wrong password")
    void loginWrongPassword() {
        authService.register(new RegisterRequest("heidi", "heidi@example.com", "password456", "Heidi"), null);

        assertThatThrownBy(() -> authService.login(new LoginRequest("heidi@example.com", "wrongpass"), null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.BAD_CREDENTIALS);
    }

    @Test
    @DisplayName("update profile changes display name and bio")
    void updateProfile() {
        authService.register(new RegisterRequest("ivan", "ivan@example.com", "pass1234", "Ivan"), null);
        Principal principal = new UsernamePasswordAuthenticationToken("ivan", null);

        UserProfileResponse updated = authService.updateProfile(principal, new UpdateProfileRequest("Ivan The Great", "Software Dev", null));
        assertThat(updated.displayName()).isEqualTo("Ivan The Great");
        assertThat(updated.bio()).isEqualTo("Software Dev");
    }

    @Test
    @DisplayName("change password verifies old password and updates hash")
    void changePassword() {
        authService.register(new RegisterRequest("judy", "judy@example.com", "oldPass1", "Judy"), null);
        Principal principal = new UsernamePasswordAuthenticationToken("judy", null);

        // wrong old password
        assertThatThrownBy(() -> authService.changePassword(principal, new ChangePasswordRequest("wrongOld", "newPass2")))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.BAD_CREDENTIALS);

        // correct old password
        authService.changePassword(principal, new ChangePasswordRequest("oldPass1", "newPass2"));

        UserProfileResponse loginRes = authService.login(new LoginRequest("judy@example.com", "newPass2"), null);
        assertThat(loginRes).isNotNull();
    }

    @Test
    @DisplayName("search users returns matching active accounts")
    void searchUsers() {
        authService.register(new RegisterRequest("kenneth", "ken@example.com", "pass123", "Ken"), null);
        authService.register(new RegisterRequest("kevin", "kevin@example.com", "pass123", "Kevin"), null);

        Page<UserSearchResult> results = authService.searchUsers("ke", 0, 10);
        assertThat(results.getContent()).extracting(UserSearchResult::username).contains("kenneth", "kevin");

        UUID kennethId = results.getContent().stream()
            .filter(user -> user.username().equals("kenneth"))
            .findFirst()
            .orElseThrow()
            .id();
        Page<UserSearchResult> withoutSelf = authService.searchUsers("ke", 0, 10, kennethId);
        assertThat(withoutSelf.getContent()).extracting(UserSearchResult::username)
            .contains("kevin")
            .doesNotContain("kenneth");
    }
}
