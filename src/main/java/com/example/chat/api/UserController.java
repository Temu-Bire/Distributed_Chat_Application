package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.dto.auth.ChangePasswordRequest;
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

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfileResponse> getProfile(Principal principal) {
        return ResponseEntity.ok(authService.getCurrentUser(principal));
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
            @RequestParam(name = "query", required = false, defaultValue = "") String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(authService.searchUsers(query, page, size));
    }
}
