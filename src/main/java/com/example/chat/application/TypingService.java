package com.example.chat.application;

import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Ephemeral "is typing" signals.
 *
 * <p>Typing state is deliberately not persisted: it is a hint, not content.
 * The service does two things a raw broadcast would not — it refuses callers
 * who are not members of the conversation (so a client cannot probe or spam
 * topics it has no access to), and it throttles each member to one frame per
 * conversation per window so a held key cannot flood the topic.</p>
 */
@Service
public class TypingService {

    private static final long THROTTLE_MILLIS = 2000;
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ConversationMemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<DistributedMessageRelay> relayProvider;
    private final ConcurrentHashMap<String, Long> lastSignal = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TypingService(ConversationMemberRepository memberRepository,
                         SimpMessagingTemplate messagingTemplate,
                         ObjectProvider<DistributedMessageRelay> relayProvider) {
        this(memberRepository, messagingTemplate, relayProvider, System::currentTimeMillis);
    }

    TypingService(ConversationMemberRepository memberRepository,
                  SimpMessagingTemplate messagingTemplate,
                  ObjectProvider<DistributedMessageRelay> relayProvider,
                  LongSupplier clock) {
        this.memberRepository = memberRepository;
        this.messagingTemplate = messagingTemplate;
        this.relayProvider = relayProvider;
        this.clock = clock;
    }

    public void notifyTyping(UUID conversationId, UUID userId, String username, boolean typing) {
        if (memberRepository.findByConversationIdAndUserId(conversationId, userId).isEmpty()) {
            throw new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation");
        }

        String key = conversationId + ":" + userId + ":" + typing;
        long now = clock.getAsLong();
        boolean[] allowed = {false};
        lastSignal.compute(key, (k, last) -> {
            if (last == null || now - last >= THROTTLE_MILLIS) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        if (!allowed[0]) {
            return;
        }
        if (lastSignal.size() > SWEEP_THRESHOLD) {
            lastSignal.values().removeIf(stamp -> now - stamp > THROTTLE_MILLIS * 4);
        }

        Map<String, Object> frame = Map.of(
            "event", "TYPING",
            "conversationId", conversationId,
            "data", Map.of("userId", userId, "username", username, "typing", typing),
            "timestamp", Instant.now().toString()
        );
        String destination = MessageRouter.conversationTopic(conversationId);
        try {
            messagingTemplate.convertAndSend(destination, frame);
        } catch (Exception ignored) {
            // A typing hint that fails to fan out is not worth failing the caller over.
        }
        try {
            DistributedMessageRelay relay = relayProvider != null ? relayProvider.getIfAvailable() : null;
            if (relay != null) {
                relay.publishCrossInstance(destination, frame);
            }
        } catch (Exception ignored) {
            // Same: presence of the relay is best-effort for ephemeral frames.
        }
    }

    void reset() {
        lastSignal.clear();
    }
}
