package com.example.chat.application;

import com.example.chat.domain.entity.MessageReactionEntity;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.AddMemberRequest;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.GroupJoinRequestRepository;
import com.example.chat.infrastructure.repository.MessageRepository;
import com.example.chat.infrastructure.repository.MessageReactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Evidence for the concurrency claim of this application: real threads hit
 * the real service layer against the real database, at the exact moments where
 * a check-then-write without locking would duplicate rows or die on a unique
 * constraint.
 *
 * <p>Each test fires a latch so the attempts overlap as tightly as the machine
 * allows, then asserts the invariant that must survive: one message per client
 * id, one membership row per pair, one DM per user pair, one reaction row per
 * (message, user, emoji).</p>
 */
@SpringBootTest
class ConcurrencyRaceTest {

    private static final int THREADS = 8;

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private GroupJoinRequestService groupJoinRequestService;
    @Autowired private AuthService authService;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ConversationMemberRepository memberRepository;
    @Autowired private MessageReactionRepository reactionRepository;
    @Autowired private GroupJoinRequestRepository groupJoinRequestRepository;

    @Test
    @DisplayName("eight concurrent sends with one clientMessageId persist exactly one message")
    void concurrentIdempotentSends() throws Exception {
        UserProfileResponse alice = user("race-alice");
        UserProfileResponse bob = user("race-bob");
        ConversationSummaryResponse conv = conversationService.getOrCreateDirect(alice.id(), bob.id());
        String clientId = "race-client-" + UUID.randomUUID();

        List<Future<PersistentMessageResponse>> futures = runConcurrently(THREADS,
            () -> messageService.sendMessage(conv.id(), alice.id(), alice.username(), "once", null, clientId));

        List<UUID> messageIds = new ArrayList<>();
        for (Future<PersistentMessageResponse> future : futures) {
            messageIds.add(future.get(20, TimeUnit.SECONDS).id());
        }

        assertThat(messageIds)
                .as("every racing retry resolves to the same stored message")
                .containsOnly(messageIds.get(0));
        assertThat(messageRepository.findByConversationIdAndClientMessageId(conv.id(), clientId))
                .isPresent();
        assertThat(messageService.getMessages(conv.id(), alice.id(), 0, 100)).hasSize(1);
    }

    @Test
    @DisplayName("six concurrent joins by the same user produce one membership row, no failures")
    void competingGroupJoins() throws Exception {
        UserProfileResponse owner = user("race-owner");
        UserProfileResponse joiner = user("race-joiner");
        ConversationSummaryResponse group = conversationService.createGroup(owner.id(),
            new CreateGroupRequest("Race group", "concurrent joins", null, List.of(), false));

        List<Future<ConversationSummaryResponse>> futures = runConcurrently(6,
            () -> conversationService.joinGroup(group.id(), joiner.id()));

        for (Future<ConversationSummaryResponse> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS).id()).isEqualTo(group.id());
        }

        long rows = memberRepository.findByConversationId(group.id()).stream()
            .filter(m -> m.getUserId().equals(joiner.id()))
            .count();
        assertThat(rows).as("uq_conv_member would have exploded; the lock must not let it").isEqualTo(1);
    }

    @Test
    @DisplayName("a join request and an admin add of the same user settle on one membership")
    void joinRequestVersusAdminAdd() throws Exception {
        UserProfileResponse owner = user("race2-owner");
        UserProfileResponse member = user("race2-member");
        ConversationSummaryResponse group = conversationService.createGroup(owner.id(),
            new CreateGroupRequest("Race group 2", "two writers", null, List.of(), false));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> join = pool.submit(() -> {
                start.await();
                conversationService.joinGroup(group.id(), member.id());
                return null;
            });
            Future<?> add = pool.submit(() -> {
                start.await();
                conversationService.addMember(group.id(), owner.id(),
                    new AddMemberRequest(member.id()));
                return null;
            });
            start.countDown();
            assertThatCode(() -> {
                join.get(20, TimeUnit.SECONDS);
                add.get(20, TimeUnit.SECONDS);
            }).doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }

        long rows = memberRepository.findByConversationId(group.id()).stream()
            .filter(m -> m.getUserId().equals(member.id()))
            .count();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("both halves of a pair opening the DM simultaneously share one conversation")
    void simultaneousDirectCreation() throws Exception {
        UserProfileResponse amy = user("race-amy");
        UserProfileResponse ben = user("race-ben");

        List<Future<ConversationSummaryResponse>> futures = runConcurrently(4, () -> {
            // Alternate direction so the two stripes collide on one pair.
            UUID[] pair = randomPair(amy, ben);
            return conversationService.getOrCreateDirect(pair[0], pair[1]);
        });

        List<UUID> ids = new ArrayList<>();
        for (Future<ConversationSummaryResponse> future : futures) {
            ids.add(future.get(20, TimeUnit.SECONDS).id());
        }
        assertThat(ids).containsOnly(ids.get(0));
    }

    @Test
    @DisplayName("concurrent toggles of one reaction never double-insert and never error")
    void concurrentReactionToggles() throws Exception {
        UserProfileResponse alice = user("race-ra");
        UserProfileResponse bob = user("race-rb");
        ConversationSummaryResponse conv = conversationService.getOrCreateDirect(alice.id(), bob.id());
        PersistentMessageResponse msg = messageService.sendMessage(conv.id(), alice.id(), alice.username(), "react race");

        List<Future<PersistentMessageResponse>> futures = runConcurrently(THREADS,
            () -> messageService.toggleReaction(msg.id(), bob.id(), "👍"));
        for (Future<PersistentMessageResponse> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }

        List<MessageReactionEntity> rows = reactionRepository.findByMessageIdIn(List.of(msg.id()));
        assertThat(rows)
                .as("%d serialized toggles from an empty start end %s", THREADS, THREADS % 2 == 0 ? "removed" : "attached")
                .hasSize(THREADS % 2);
    }

    @Test
    @DisplayName("one join request accepted by two admins concurrently is accepted once")
    void doubleAcceptOfJoinRequest() throws Exception {
        UserProfileResponse owner = user("race3-owner");
        UserProfileResponse applicant = user("race3-app");
        ConversationSummaryResponse group = conversationService.createGroup(owner.id(),
            new CreateGroupRequest("Race group 3", "double accept", null, List.of(), false));

        UUID requestId = groupJoinRequestRepository.save(
            new com.example.chat.domain.entity.GroupJoinRequestEntity(group.id(), applicant.id())).getId();

        var futures = runConcurrently(4, () -> {
            try {
                groupJoinRequestService.acceptJoinRequest(owner.id(), requestId);
            } catch (ChatException serialized) {
                // The loser of the row lock re-reads the request after the
                // winner committed and correctly finds it "not pending".
                assertThat(serialized.code()).isEqualTo(ChatException.INVALID_COMMAND);
            }
            return null;
        });
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }

        long membershipRows = memberRepository.findByConversationId(group.id()).stream()
            .filter(m -> m.getUserId().equals(applicant.id()))
            .count();
        assertThat(membershipRows).isEqualTo(1);
        assertThat(groupJoinRequestRepository.findById(requestId).orElseThrow().getStatus())
            .isEqualTo(com.example.chat.domain.entity.GroupJoinRequestStatus.ACCEPTED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID[] randomPair(UserProfileResponse a, UserProfileResponse b) {
        return System.nanoTime() % 2 == 0
            ? new UUID[] {a.id(), b.id()}
            : new UUID[] {b.id(), a.id()};
    }

    private UserProfileResponse user(String prefix) {
        String raw = prefix + UUID.randomUUID().toString().substring(0, 8);
        String name = raw.length() > 20 ? raw.substring(0, 20) : raw;
        return authService.register(new RegisterRequest(name, name + "@race.local", "pass123", name), null);
    }

    private <T> List<Future<T>> runConcurrently(int threads, java.util.concurrent.Callable<T> body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return body.call();
                }));
            }
            start.countDown();
            return futures;
        } finally {
            pool.shutdown();
        }
    }
}
