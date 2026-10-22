package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.application.FriendService;
import com.example.chat.application.ProfileQueryService;
import com.example.chat.dto.auth.ChangePasswordRequest;
import com.example.chat.dto.auth.PublicProfileResponse;
import com.example.chat.dto.auth.UpdateProfileRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.auth.UserSearchResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;
    private final FriendService friendService;
    private final ProfileQueryService profileQueryService;

    public UserController(AuthService authService, FriendService friendService,
                          ProfileQueryService profileQueryService) {
        this.authService = authService;
        this.friendService = friendService;
        this.profileQueryService = profileQueryService;
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(Principal principal) {
        return ResponseEntity.ok(authService.getCurrentUser(principal));
    }

    /**
     * The profile card of another account. Literal paths above and below this
     * template win over it, so {@code /profile} and {@code /search} keep their
     * own handlers; the id is matched exactly and never falls back to a name.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PublicProfileResponse> getPublicProfile(
            Principal principal,
            @PathVariable("id") UUID targetUserId) {
        UserProfileResponse viewer = authService.getCurrentUser(principal);
        return ResponseEntity.ok(profileQueryService.findProfile(viewer.id(), targetUserId));
    }

    @PutMapping("/profile")
    public ResponseEntity<UserProfileResponse> updateProfile(Principal principal,
                                                             @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(authService.updateProfile(principal, request));
    }

    @PutMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(Principal principal,
                                                              @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal, request);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @DeleteMapping("/account")
    public ResponseEntity<Map<String, String>> deactivateAccount(Principal principal,
                                                                 HttpServletRequest httpRequest) {
        authService.deactivateAccount(principal, httpRequest);
        return ResponseEntity.ok(Map.of("message", "Account deactivated successfully"));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<UserSearchResult>> searchUsers(
            Principal principal,
            @RequestParam(name = "query", required = false, defaultValue = "") String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        UserProfileResponse current = authService.getCurrentUser(principal);
        UUID currentId = current.id();
        Page<UserSearchResult> results = authService.searchUsers(query, page, size, currentId)
            .map(user -> user.withFriendshipStatus(
                friendService.getFriendshipStatus(currentId, user.id()).status()
            ));
        return ResponseEntity.ok(results);
    }
}
