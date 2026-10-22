package com.example.chat.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one thing a two-node deployment depends on: a frame that leaves node A
 * has to arrive on node B's broker under the same destination and with the same
 * body, and must never bounce back onto A's own subscribers.
 */
class DistributedMessageRelayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpMessagingTemplate brokerA = mock(SimpMessagingTemplate.class);
    private final SimpMessagingTemplate brokerB = mock(SimpMessagingTemplate.class);

    private final DistributedMessageRelay nodeA = new DistributedMessageRelay(redis, brokerA, mapper);
    private final DistributedMessageRelay nodeB = new DistributedMessageRelay(redis, brokerB, mapper);

    @Test
    @DisplayName("a frame published on one node is replayed on the other with its destination intact")
    void frameReachesTheOtherNode() throws Exception {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("event", "NEW_MESSAGE");
        frame.put("conversationId", "conv-1");
        frame.put("data", Map.of("content", "hello from node A"));

        nodeA.publishCrossInstance("/topic/conversations/conv-1", frame);

        String wire = publishedEnvelope();
        nodeB.onMessage(envelope(wire), null);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(brokerB).convertAndSend(eq("/topic/conversations/conv-1"), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(Map.class);
        Map<?, ?> received = (Map<?, ?>) captor.getValue();
        assertThat(received.get("event")).isEqualTo("NEW_MESSAGE");
        assertThat(received.get("conversationId")).isEqualTo("conv-1");

        // The originating node already published locally; echoing its own
        // envelope back would double-deliver the message to its subscribers.
        nodeA.onMessage(envelope(wire), null);
        verifyNoInteractions(brokerA);
    }

    @Test
    @DisplayName("a per-user notification topic crosses nodes like any other topic")
    void notificationTopicCrossesNodes() throws Exception {
        nodeA.publishCrossInstance("/topic/notifications/user-9", Map.of("type", "MENTION"));

        nodeB.onMessage(envelope(publishedEnvelope()), null);

        verify(brokerB).convertAndSend(eq("/topic/notifications/user-9"), any(Object.class));
    }

    @Test
    @DisplayName("junk on the cluster channel is dropped instead of killing the listener")
    void unreadableEnvelopeIsIgnored() {
        assertThatCode(() -> nodeB.onMessage(envelope("<html>not an envelope</html>"), null))
            .doesNotThrowAnyException();

        verifyNoInteractions(brokerB);
    }

    @Test
    @DisplayName("an envelope without a destination is ignored")
    void envelopeWithoutDestinationIsIgnored() throws Exception {
        String json = mapper.writeValueAsString(Map.of("instanceId", "someone-else", "payload", "x"));

        nodeB.onMessage(envelope(json), null);

        verifyNoInteractions(brokerB);
    }

    /** Pulls the single JSON string this node pushed onto the cluster channel. */
    private String publishedEnvelope() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(redis).convertAndSend(eq(DistributedMessageRelay.CHANNEL), captor.capture());
        return String.valueOf(captor.getValue());
    }

    /** The shape Redis delivers to a {@code MessageListener}. */
    private static Message envelope(String json) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
