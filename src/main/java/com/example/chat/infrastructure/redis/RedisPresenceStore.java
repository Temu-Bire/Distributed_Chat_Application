package com.example.chat.infrastructure.redis;

import com.example.chat.application.PresenceStore;
import com.example.chat.domain.ChatUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Distributed, Redis-backed {@link PresenceStore}.
 *
 * <p>Shared across all application instances in a distributed cluster so that
 * user presence, online counts, and duplicate-name checks work seamlessly across nodes.</p>
 */
@Component
@ConditionalOnProperty(name = "chat.distributed.enabled", havingValue = "true")
public class RedisPresenceStore implements PresenceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPresenceStore.class);

    private static final String KEY_USERS_BY_NAME = "chat:presence:users";
    private static final String KEY_USERS_BY_SESSION = "chat:presence:sessions";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisPresenceStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean register(ChatUser user) {
        String lowerName = user.username().toLowerCase(Locale.ROOT);
        String json;
        try {
            json = objectMapper.writeValueAsString(user);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ChatUser for Redis presence", e);
            return false;
        }

        // Atomic put if absent on username
        Boolean claimed = redisTemplate.opsForHash().putIfAbsent(KEY_USERS_BY_NAME, lowerName, json);
        if (Boolean.TRUE.equals(claimed)) {
            redisTemplate.opsForHash().put(KEY_USERS_BY_SESSION, user.sessionId(), json);
            return true;
        }

        // If the same session re-registered with same name, allow update
        Object existingJson = redisTemplate.opsForHash().get(KEY_USERS_BY_NAME, lowerName);
        if (existingJson != null) {
            try {
                ChatUser existing = objectMapper.readValue(existingJson.toString(), ChatUser.class);
                if (existing.sessionId().equals(user.sessionId())) {
                    redisTemplate.opsForHash().put(KEY_USERS_BY_NAME, lowerName, json);
                    redisTemplate.opsForHash().put(KEY_USERS_BY_SESSION, user.sessionId(), json);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    @Override
    public Optional<ChatUser> unregister(String sessionId) {
        Object json = redisTemplate.opsForHash().get(KEY_USERS_BY_SESSION, sessionId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            ChatUser user = objectMapper.readValue(json.toString(), ChatUser.class);
            redisTemplate.opsForHash().delete(KEY_USERS_BY_SESSION, sessionId);
            redisTemplate.opsForHash().delete(KEY_USERS_BY_NAME, user.username().toLowerCase(Locale.ROOT));
            return Optional.of(user);
        } catch (Exception e) {
            log.error("Failed to deserialize ChatUser on unregister", e);
            redisTemplate.opsForHash().delete(KEY_USERS_BY_SESSION, sessionId);
            return Optional.empty();
        }
    }

    @Override
    public Optional<ChatUser> findBySessionId(String sessionId) {
        Object json = redisTemplate.opsForHash().get(KEY_USERS_BY_SESSION, sessionId);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json.toString(), ChatUser.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ChatUser> findByUsername(String username) {
        Object json = redisTemplate.opsForHash().get(KEY_USERS_BY_NAME, username.toLowerCase(Locale.ROOT));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json.toString(), ChatUser.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ChatUser> findAll() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(KEY_USERS_BY_NAME);
        List<ChatUser> users = new ArrayList<>();
        for (Object json : entries.values()) {
            try {
                users.add(objectMapper.readValue(json.toString(), ChatUser.class));
            } catch (Exception ignored) {}
        }
        users.sort(Comparator.comparing(ChatUser::username, String.CASE_INSENSITIVE_ORDER));
        return users;
    }
}
