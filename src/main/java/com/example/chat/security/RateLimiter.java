package com.example.chat.security;

import com.example.chat.config.RateLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fixed-window request counter guarding the endpoints attackers hammer.
 *
 * <p>One minute-long window per key; the first request opens the window and
 * every request inside it counts against the budget, whatever it looks like to
 * the caller. {@link ConcurrentHashMap#compute} makes the read-modify-write
 * atomic, so two threads racing on the same key can never both observe a slot
 * that only existed once - which is the whole point of a limiter under
 * concurrent traffic.</p>
 *
 * <p>State is per instance and deliberately in-memory: a restart resets the
 * windows (acceptable - the attacker simply gets a fresh budget, the endpoint
 * stays protected), and multi-instance deployments get per-node budgets
 * (documented in {@code docs/architecture.md} as the Redis upgrade path).</p>
 */
@Component
public class RateLimiter {

    /** {@code retryAfterSeconds} is meaningful only when {@code allowed} is false. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        private static final Decision OPEN = new Decision(true, 0);
    }

    private static final long WINDOW_MILLIS = 60_000L;

    /** Above this many tracked keys, expired windows are evicted on the next request. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final RateLimitProperties properties;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public RateLimiter(RateLimitProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    /** Test seam: a controllable clock so window expiry is not a 60-second sleep. */
    RateLimiter(RateLimitProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Decision forLogin(String clientKey) {
        return tryAcquire("login:" + clientKey, properties.loginPerMinute());
    }

    public Decision forRegister(String clientKey) {
        return tryAcquire("register:" + clientKey, properties.registerPerMinute());
    }

    public Decision forFriendRequest(String clientKey) {
        return tryAcquire("friend:" + clientKey, properties.friendRequestPerMinute());
    }

    public Decision forMessage(Object senderId) {
        return tryAcquire("message:" + senderId, properties.messagesPerMinute());
    }

    /**
     * Counts one request against {@code key}'s current window.
     * A non-positive limit means "unlimited"; the master switch in
     * {@link RateLimitProperties} short-circuits everything.
     */
    public Decision tryAcquire(String key, int limit) {
        if (!properties.enabled() || limit <= 0) {
            return Decision.OPEN;
        }

        if (windows.size() > SWEEP_THRESHOLD) {
            long sweepNow = clock.getAsLong();
            windows.entrySet().removeIf(entry -> sweepNow - entry.getValue().startMillis() >= WINDOW_MILLIS);
        }

        long now = clock.getAsLong();
        Window updated = windows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startMillis() >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });

        if (updated.count() <= limit) {
            return new Decision(true, 0);
        }
        long remainingMillis = updated.startMillis() + WINDOW_MILLIS - clock.getAsLong();
        return new Decision(false, Math.max(1, (remainingMillis + 999) / 1000));
    }

    /** Visible for tests: forget every window, as if the minute had rolled over. */
    public void reset() {
        windows.clear();
    }

    private record Window(long startMillis, int count) {
    }
}
