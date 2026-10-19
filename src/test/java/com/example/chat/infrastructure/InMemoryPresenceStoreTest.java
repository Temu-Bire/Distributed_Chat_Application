package com.example.chat.infrastructure;

import com.example.chat.domain.ChatUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the in-memory presence store.
 *
 * <p>Concurrency is covered separately in
 * {@code DuplicateUsernameConcurrencyTest}; this class pins down the single
 * threaded semantics: case-insensitive uniqueness, re-joining, and removal.</p>
 */
class InMemoryPresenceStoreTest {

    private InMemoryPresenceStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryPresenceStore();
    }

    private static ChatUser user(String username, String sessionId) {
        return new ChatUser(username, sessionId, "anon-" + sessionId);
    }

    @Test
    void registersAndFindsBySessionAndByUsername() {
        assertThat(store.register(user("alice", "s1"))).isTrue();

        assertThat(store.findBySessionId("s1")).contains(user("alice", "s1"));
        assertThat(store.findByUsername("alice")).contains(user("alice", "s1"));
    }

    @Test
    @DisplayName("username lookup ignores case")
    void lookupIsCaseInsensitive() {
        store.register(user("Alice", "s1"));

        assertThat(store.findByUsername("alice")).isPresent();
        assertThat(store.findByUsername("ALICE")).isPresent();
    }

    @Test
    void rejectsANameAlreadyHeldByAnotherConnection() {
        store.register(user("alice", "s1"));

        assertThat(store.register(user("alice", "s2"))).isFalse();
        assertThat(store.findByUsername("alice").orElseThrow().sessionId()).isEqualTo("s1");
    }

    @Test
    void rejectsACaseVariantHeldByAnotherConnection() {
        store.register(user("alice", "s1"));

        assertThat(store.register(user("ALICE", "s2"))).isFalse();
        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void allowsANameAgainAfterItIsReleased() {
        store.register(user("alice", "s1"));
        store.unregister("s1");

        assertThat(store.register(user("alice", "s2"))).isTrue();
        assertThat(store.findByUsername("alice").orElseThrow().sessionId()).isEqualTo("s2");
    }

    @Test
    void rejoiningUnderANewNameReplacesTheOldEntry() {
        store.register(user("alice", "s1"));

        assertThat(store.register(user("bob", "s1"))).isTrue();

        assertThat(store.findAll()).containsExactly(user("bob", "s1"));
        assertThat(store.findByUsername("alice")).isEmpty();
    }

    @Test
    void unregisterReturnsTheRemovedUser() {
        store.register(user("alice", "s1"));

        assertThat(store.unregister("s1")).contains(user("alice", "s1"));
        assertThat(store.findBySessionId("s1")).isEmpty();
        assertThat(store.findByUsername("alice")).isEmpty();
    }

    @Test
    void unregisterOfAnUnknownSessionIsEmpty() {
        assertThat(store.unregister("missing")).isEmpty();
    }

    @Test
    void findAllIsSortedByUsernameIgnoringCase() {
        store.register(user("carol", "s3"));
        store.register(user("alice", "s1"));
        store.register(user("Bob", "s2"));

        assertThat(store.findAll())
                .extracting(ChatUser::username)
                .containsExactly("alice", "Bob", "carol");
    }

    @Test
    void findByUsernameRejectsNull() {
        assertThat(store.findByUsername(null)).isEmpty();
    }

    @Test
    void keepsTheMessagingHandleWithTheUser() {
        store.register(new ChatUser("alice", "s1", "anon-xyz"));

        assertThat(store.findByUsername("alice").orElseThrow().messagingUser()).isEqualTo("anon-xyz");
    }
}
