package com.example.chat.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Distributes chat messages and presence events across multiple Spring Boot nodes
 * using Redis Pub/Sub.
 */
@Component
@ConditionalOnProperty(name = "chat.distributed.enabled", havingValue = "true")
public class DistributedMessageRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DistributedMessageRelay.class);
    public static final String CHANNEL = "chat:cluster:events";

    private final String instanceId = UUID.randomUUID().toString();
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public DistributedMessageRelay(StringRedisTemplate redisTemplate,
                                  SimpMessagingTemplate messagingTemplate,
                                  ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /** Publishes a message to all cluster nodes through Redis. */
    public void publishCrossInstance(String destination, Object payload) {
        try {
            Map<String, Object> envelope = Map.of(
                "instanceId", instanceId,
                "destination", destination,
                "payload", payload
            );
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish cross-instance message to Redis", e);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            Map<?, ?> envelope = objectMapper.readValue(json, Map.class);

            String senderInstance = (String) envelope.get("instanceId");
            // Avoid echoing messages sent by this same node
            if (this.instanceId.equals(senderInstance)) {
                return;
            }

            String destination = (String) envelope.get("destination");
            Object payload = envelope.get("payload");

            if (destination != null && payload != null) {
                messagingTemplate.convertAndSend(destination, payload);
            }
        } catch (Exception e) {
            log.error("Failed to process cross-instance Redis message", e);
        }
    }

    public String getInstanceId() {
        return instanceId;
    }
}
