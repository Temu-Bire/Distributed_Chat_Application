package com.example.chat.api;

import com.example.chat.application.AuthService;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.exception.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/uploads")
public class FileUploadController {

    private final AuthService authService;

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    public FileUploadController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/avatar")
    public ResponseEntity<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file, Principal principal) {
        if (principal == null) {
            throw new ChatException(ChatException.UNAUTHORIZED, "Not authenticated");
        }
        UserProfileResponse user = authService.getCurrentUser(principal);

        if (file.isEmpty()) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "File cannot be empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Only image files are allowed");
        }

        try {
            Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(uploadPath);

            String originalFilename = file.getOriginalFilename();
            String extension = ".jpg";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String filename = "avatar_" + user.id() + "_" + UUID.randomUUID() + extension;
            Path filePath = uploadPath.resolve(filename);

            Files.copy(file.getInputStream(), filePath);

            String fileUrl = "/uploads/" + filename;
            return ResponseEntity.ok(Map.of("url", fileUrl));
        } catch (IOException e) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Failed to upload file");
        }
    }
}
