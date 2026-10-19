package com.example.chat.application;

import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.InMemoryPresenceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a display name can be claimed by exactly one connection even when
 * many connections ask for it at the same instant.
 *
 * <p>This is the property that the original {@code ChatServer} protected with a
 * {@code synchronized} method. Here it is provided by a single atomic
 * {@code putIfAbsent}, and this test is what makes that claim checkable rather
 * than aspirational. Every thread is released from one latch so the calls really
 * do overlap.</p>
 */
class DuplicateUsernameConcurrencyTest {

    private static final int ATTEMPTS = 32;

    @Test
    @DisplayName("many simultaneous joins for one name produce exactly one winner")
    void onlyOneConcurrentJoinSucceeds() throws Exception {
        PresenceService presence = new PresenceService(new InMemoryPresenceStore());
        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        try {
            for (int i = 0; i < ATTEMPTS; i++) {
                String sessionId = "session-" + i;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        presence.connect(sessionId, "anon-" + sessionId, "alice");
                        return true;
                    } catch (ChatException expected) {
                        return false;
                    }
                }));
            }

            start.countDown();

            int winners = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertThat(winners).as("exactly one thread may register the name").isEqualTo(1);
            assertThat(presence.onlineUsernames()).containsExactly("alice");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("mixed-case simultaneous joins for one name produce exactly one winner")
    void onlyOneConcurrentJoinSucceedsAcrossCaseVariants() throws Exception {
        PresenceService presence = new PresenceService(new InMemoryPresenceStore());
        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger();

        String[] spellings = {"alice", "ALICE", "Alice", "aLiCe"};

        try {
            for (int i = 0; i < ATTEMPTS; i++) {
                String sessionId = "session-" + i;
                String spelling = spellings[counter.getAndIncrement() % spellings.length];
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        presence.connect(sessionId, "anon-" + sessionId, spelling);
                        return true;
                    } catch (ChatException expected) {
                        return false;
                    }
                }));
            }

            start.countDown();

            int winners = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertThat(winners).as("case-insensitive uniqueness").isEqualTo(1);
            assertThat(presence.onlineUsernames()).hasSize(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("distinct names all register when they race")
    void distinctNamesAllRegister() throws Exception {
        PresenceService presence = new PresenceService(new InMemoryPresenceStore());
        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();

        try {
            for (int i = 0; i < ATTEMPTS; i++) {
                String sessionId = "session-" + i;
                String username = "user" + i;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    presence.connect(sessionId, "anon-" + sessionId, username);
                    return true;
                }));
            }

            start.countDown();

            for (Future<Boolean> outcome : outcomes) {
                assertThat(outcome.get(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(presence.onlineCount()).isEqualTo(ATTEMPTS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
