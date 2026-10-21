package com.example.chat.application;

import com.example.chat.exception.ChatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only way a file reaches disk. Content type is decided by magic bytes,
 * the stored name is generated here, and every minted file keeps a .meta
 * sidecar so a message can reference an attachment the server actually owns
 * instead of trusting a client-supplied URL.
 */
@Service
public class UploadService {

    public record StoredUpload(String url, String name, String type, long size) {
    }

    private static final Pattern STORED = Pattern.compile("/uploads/([a-z]+_[0-9a-f-]{36})\\.([a-z0-9]+)");
    private static final Map<String, String> MAGIC = new LinkedHashMap<>();

    static {
        MAGIC.put("89504e470d0a1a0a", "png");
        MAGIC.put("ffd8ff", "jpg");
        MAGIC.put("474946383761", "gif");
        MAGIC.put("474946383961", "gif");
        MAGIC.put("52494646", "webp");
        MAGIC.put("255044462d", "pdf");
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private final Path uploadRoot;
    private final long maxBytes;

    public UploadService(@Value("${app.upload.dir:./uploads}") String uploadDir,
                         @Value("${app.upload.max-bytes:10485760}") long maxBytes) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    public StoredUpload store(MultipartFile file, String prefix, String displayName) {
        if (file == null || file.isEmpty()) {
            throw new ChatException(ChatException.INVALID_UPLOAD, "File is empty");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ChatException(ChatException.INVALID_UPLOAD, "File could not be read");
        }
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new ChatException(ChatException.INVALID_UPLOAD,
                "File must be between 1 byte and " + (maxBytes / (1024 * 1024)) + " MB");
        }
        String extension = sniff(bytes);
        if (extension == null) {
            throw new ChatException(ChatException.INVALID_UPLOAD,
                "Only PNG, JPEG, GIF, WEBP and PDF files are accepted");
        }

        String stem = prefix + "_" + UUID.randomUUID();
        Path target = uploadRoot.resolve(stem + "." + extension).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new ChatException(ChatException.INVALID_UPLOAD, "Rejected file name");
        }
        String safeName = sanitize(displayName, extension);
        try {
            Files.createDirectories(uploadRoot);
            Files.write(target, bytes);
            Files.writeString(uploadRoot.resolve(stem + ".meta"),
                "type=" + contentType(extension) + "\nname=" + safeName + "\nsize=" + bytes.length + "\n");
        } catch (IOException e) {
            throw new ChatException(ChatException.INVALID_UPLOAD, "File could not be stored");
        }
        return new StoredUpload("/uploads/" + stem + "." + extension, safeName, contentType(extension), bytes.length);
    }

    /**
     * Resolves a URL previously minted by {@link #store}. Returns null for
     * anything else, so callers can reject references to files this server
     * never produced (remote hotlinks, traversal, other users' uploads).
     */
    public StoredUpload describe(String url) {
        if (url == null) {
            return null;
        }
        Matcher matcher = STORED.matcher(url);
        if (!matcher.matches() || !url.startsWith("/uploads/")) {
            return null;
        }
        Path stem = uploadRoot.resolve(matcher.group(1));
        try {
            boolean present = false;
            for (String extension : new String[] { "png", "jpg", "gif", "webp", "pdf" }) {
                if (Files.exists(stem.resolveSibling(stem.getFileName() + "." + extension))) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                return null;
            }
            String meta = Files.readString(stem.resolveSibling(stem.getFileName() + ".meta"), StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : meta.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    values.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            String type = values.getOrDefault("type", "application/octet-stream");
            String extension = url.substring(url.lastIndexOf('.') + 1);
            if (!contentType(extension).equals(type)) {
                return null;
            }
            return new StoredUpload(url, values.getOrDefault("name", url.split("/")[2]),
                type, Long.parseLong(values.getOrDefault("size", "0")));
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static String sanitize(String displayName, String extension) {
        String base = displayName == null || displayName.isBlank() ? "file." + extension : displayName.trim();
        base = base.replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ").replace('/', ' ').replace('\\', ' ');
        if (base.length() > 140) {
            base = base.substring(0, 140);
        }
        return base;
    }

    private static String sniff(byte[] bytes) {
        String hex = toHex(bytes, 12);
        for (Map.Entry<String, String> entry : MAGIC.entrySet()) {
            if (hex.startsWith(entry.getKey())) {
                if (entry.getValue().equals("webp")) {
                    // RIFF alone is shared by WAV/AVI; the WEBP tag sits at bytes 8..12.
                    return toHex(bytes, 12).substring(16).startsWith("57454250") ? "webp" : null;
                }
                return entry.getValue();
            }
        }
        return null;
    }

    private static String toHex(byte[] bytes, int limit) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, limit); i++) {
            hex.append(String.format("%02x", bytes[i]));
        }
        return hex.toString();
    }
}
