package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.application.UploadService;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.exception.ChatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/uploads")
public class FileUploadController {

    private final AuthService authService;
    private final UploadService uploadService;

    public FileUploadController(AuthService authService, UploadService uploadService) {
        this.authService = authService;
        this.uploadService = uploadService;
    }

    @PostMapping("/avatar")
    public ResponseEntity<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file, Principal principal) {
        if (principal == null) {
            throw new ChatException(ChatException.UNAUTHORIZED, "Not authenticated");
        }
        UserProfileResponse user = authService.getCurrentUser(principal);
        UploadService.StoredUpload stored = uploadService.store(file, "avatar", user.username() + "-avatar");
        return ResponseEntity.ok(Map.of("url", stored.url()));
    }

    @PostMapping("/media")
    public ResponseEntity<UploadService.StoredUpload> uploadMedia(@RequestParam("file") MultipartFile file, Principal principal) {
        if (principal == null) {
            throw new ChatException(ChatException.UNAUTHORIZED, "Not authenticated");
        }
        authService.getCurrentUser(principal);
        return ResponseEntity.status(HttpStatus.CREATED).body(uploadService.store(file, "media", safeName(file)));
    }

    private static String safeName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "attachment" : name;
    }
}
