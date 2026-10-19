package com.example.chat.infrastructure;

import com.example.chat.application.PresenceStore;
import com.example.chat.domain.ChatUser;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PresenceStore} for a single application instance.
 *
 * <h3>Why this is thread-safe without a global lock</h3>
 * <p>Both maps are {@link ConcurrentHashMap}s. Uniqueness of a display name is
 * enforced by the single atomic {@link ConcurrentHashMap#putIfAbsent} call in
 * {@link #register}, which is what makes two simultaneous join attempts for the
 * same name safe: exactly one of them observes a {@code null} return value and
 * wins. Guarding the check and the insert with a {@code synchronized} block or
 * a lock would also work, but this keeps the whole critical section lock-free
 * and therefore never blocks unrelated readers.</p>
 *
 * <p>No lock is ever held across network I/O here — the mutating operations are
 * pure map writes — so a slow client can never stall presence updates for
 * other clients.</p>
 *
 * <h3>What this does not solve</h3>
 * <p>The store is process-local. Two application instances have two independent
 * copies, which is why presence must move to shared storage before the app is
 * scaled out (see {@code docs/architecture.md}).</p>
 */
@Component
public class InMemoryPresenceStore implements PresenceStore {

    /** connection id → user */
    private final Map<String, ChatUser> bySessionId = new ConcurrentHashMap<>();

    /** lower-cased display name → user, for duplicate detection */
    private final Map<String, ChatUser> byUsername = new ConcurrentHashMap<>();

    @Override
    public boolean register(ChatUser user) {
        // A connection that joins again replaces its own earlier entry, so a
        // reconnect never leaves a ghost behind for the same session id.
        unregister(user.sessionId());

        if (byUsername.putIfAbsent(key(user.username()), user) != null) {
            return false;
        }
        bySessionId.put(user.sessionId(), user);
        return true;
    }

    @Override
    public Optional<ChatUser> unregister(String sessionId) {
        ChatUser removed = bySessionId.remove(sessionId);
        if (removed != null) {
            // Two-argument remove: only drop the name index entry if it still
            // points at this user, so a newer holder is never evicted.
            byUsername.remove(key(removed.username()), removed);
        }
        return Optional.ofNullable(removed);
    }

    @Override
    public Optional<ChatUser> findBySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    @Override
    public Optional<ChatUser> findByUsername(String username) {
        return username == null
                ? Optional.empty()
                : Optional.ofNullable(byUsername.get(key(username)));
    }

    @Override
    public List<ChatUser> findAll() {
        return bySessionId.values().stream()
                .sorted(Comparator.comparing(ChatUser::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
