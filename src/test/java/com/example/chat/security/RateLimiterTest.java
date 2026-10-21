package com.example.chat.security;

import com.example.chat.config.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the fixed-window counter, including the property the whole
 * design rests on: under concurrent traffic exactly {@code limit} requests
 * pass and no more - the read-modify-write must not lose updates.
 */
class RateLimiterTest {

    private static final int WINDOW_SECONDS = 60;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private RateLimiter limiter(boolean enabled, int loginPerMinute) {
        return new RateLimiter(new RateLimitProperties(enabled, loginPerMinute, 5, 20, 60), now::get);
    }

    @Test
    @DisplayName("the budget allows exactly the limit and refuses the next attempt")
    void budgetAllowsUpToTheLimitThenRefuses() {
        RateLimiter limiter = limiter(true, 3);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.forLogin("203.0.113.7").allowed()).isTrue();
        }
        RateLimiter.Decision refused = limiter.forLogin("203.0.113.7");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, (long) WINDOW_SECONDS);
    }

    @Test
    @DisplayName("the budget renews once the window rolls over")
    void windowRollsOver() {
        RateLimiter limiter = limiter(true, 1);
        assertThat(limiter.forLogin("203.0.113.7").allowed()).isTrue();
        assertThat(limiter.forLogin("203.0.113.7").allowed()).isFalse();

        now.addAndGet((WINDOW_SECONDS + 1) * 1000L);
        assertThat(limiter.forLogin("203.0.113.7").allowed()).isTrue();
    }

    @Test
    @DisplayName("Retry-After counts the real remaining time, not a constant")
    void retryAfterReflectsRemainingWindow() {
        RateLimiter limiter = limiter(true, 1);
        limiter.forLogin("203.0.113.7");

        now.addAndGet(30_000L);
        assertThat(limiter.forLogin("203.0.113.7").retryAfterSeconds()).isEqualTo(30L);

        now.addAndGet(29_000L);
        assertThat(limiter.forLogin("203.0.113.7").retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    @DisplayName("different keys keep different budgets")
    void keysAreIndependent() {
        RateLimiter limiter = limiter(true, 1);
        assertThat(limiter.forLogin("203.0.113.1").allowed()).isTrue();
        assertThat(limiter.forLogin("203.0.113.1").allowed()).isFalse();
        assertThat(limiter.forLogin("203.0.113.2").allowed())
                .as("a second IP is not punished for the first one's attempts")
                .isTrue();
    }

    @Test
    @DisplayName("the different endpoint families have separate budgets")
    void loginAndRegisterDoNotShareCounters() {
        RateLimiter limiter = limiter(true, 1);
        assertThat(limiter.forLogin("203.0.113.7").allowed()).isTrue();
        assertThat(limiter.forRegister("203.0.113.7").allowed()).isTrue();
        assertThat(limiter.forFriendRequest("user:amy").allowed()).isTrue();
        assertThat(limiter.forMessage("uuid-1").allowed()).isTrue();
    }

    @Test
    @DisplayName("a disabled limiter never refuses anything")
    void disabledLimiterIsOpen() {
        RateLimiter limiter = limiter(false, 1);
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.forLogin("203.0.113.7").allowed()).isTrue();
        }
    }

    @Test
    @DisplayName("concurrent requests against a budget of N let exactly N through")
    void concurrentTrafficSharesOneBudget() throws Exception {
        RateLimiter limiter = new RateLimiter(
                new RateLimitProperties(true, 1, 1, 1, 1)); // budget irrelevant, tryAcquire passes its own

        int limit = 50;
        int threads = 16;
        int callsPerThread = 10;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        if (limiter.tryAcquire("burst", limit).allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(allowed.get())
                .as("%d concurrent attempts against a budget of %d", threads * callsPerThread, limit)
                .isEqualTo(limit);
    }
}
