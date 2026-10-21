package com.example.chat.application;

import com.example.chat.domain.entity.UserEntity;
import com.example.chat.domain.entity.UserSettingsEntity;
import com.example.chat.dto.auth.*;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.UserRepository;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;
    private final PasswordEncoder passwordEncoder;
    private final PresenceService presenceService;

    public AuthService(UserRepository userRepository,
                       UserSettingsRepository settingsRepository,
                       PasswordEncoder passwordEncoder,
                       PresenceService presenceService) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.passwordEncoder = passwordEncoder;
        this.presenceService = presenceService;
    }

    @Transactional
    public UserProfileResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String username = request.username().trim();

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new ChatException(ChatException.DUPLICATE_USERNAME, "Username is already taken");
        }

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ChatException(ChatException.DUPLICATE_EMAIL, "Email is already registered");
        }

        String encodedPassword = passwordEncoder.encode(request.password());
        UserEntity user = new UserEntity(username, normalizedEmail, encodedPassword, request.displayName().trim());
        user = userRepository.save(user);

        UserSettingsEntity settings = new UserSettingsEntity(user.getId());
        settingsRepository.save(settings);

        establishSession(user, httpRequest);
        return UserProfileResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserProfileResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);

        UserEntity user = userRepository.findByEmailIgnoreCase(normalizedEmail)
            .orElseThrow(() -> new ChatException(ChatException.BAD_CREDENTIALS, "Invalid email or password"));

        if (user.isDeactivated()) {
            throw new ChatException(ChatException.UNAUTHORIZED, "Account is deactivated");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ChatException(ChatException.BAD_CREDENTIALS, "Invalid email or password");
        }

        establishSession(user, httpRequest);
        return UserProfileResponse.from(user);
    }

    public void logout(HttpServletRequest httpRequest) {
        SecurityContextHolder.clearContext();
        if (httpRequest != null) {
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUser(Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new ChatException(ChatException.UNAUTHORIZED, "Not authenticated");
        }
        UserEntity user = userRepository.findByUsernameIgnoreCase(principal.getName())
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));

        if (user.isDeactivated()) {
            throw new ChatException(ChatException.UNAUTHORIZED, "Account is deactivated");
        }
        return UserProfileResponse.from(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(Principal principal, UpdateProfileRequest request) {
        UserProfileResponse current = getCurrentUser(principal);
        UserEntity user = userRepository.findById(current.id())
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.bio() != null) {
            user.setBio(request.bio().trim());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl().trim());
        }
        user = userRepository.save(user);
        return UserProfileResponse.from(user);
    }

    @Transactional
    public void changePassword(Principal principal, ChangePasswordRequest request) {
        UserProfileResponse current = getCurrentUser(principal);
        UserEntity user = userRepository.findById(current.id())
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new ChatException(ChatException.BAD_CREDENTIALS, "Incorrect current password");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    @Transactional
    public void deactivateAccount(Principal principal, HttpServletRequest httpRequest) {
        UserProfileResponse current = getCurrentUser(principal);
        UserEntity user = userRepository.findById(current.id())
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));

        user.setDeactivated(true);
        userRepository.save(user);
        logout(httpRequest);
    }

    @Transactional(readOnly = true)
    public Page<UserSearchResult> searchUsers(String query, int page, int size) {
        return searchUsers(query, page, size, null);
    }

    @Transactional(readOnly = true)
    public Page<UserSearchResult> searchUsers(String query, int page, int size, UUID excludeUserId) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.clamp(size, 1, 50);

        Page<UserEntity> users = userRepository.searchUsers(
            query == null ? "" : query.trim(),
            excludeUserId,
            PageRequest.of(boundedPage, boundedSize, Sort.by("displayName").ascending())
        );
        return users.map(user -> {
            boolean isOnline = presenceService.isOnline(user.getUsername());
            return UserSearchResult.from(user, isOnline);
        });
    }

    private void establishSession(UserEntity user, HttpServletRequest httpRequest) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            user.getUsername(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        if (httpRequest != null) {
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }
    }
}
