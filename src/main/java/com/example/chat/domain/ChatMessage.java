package com.example.chat.domain;

import java.time.Instant;

/**
 * A single chat message, produced by the application layer and handed to the
 * transport layer for delivery.
 *
 * <p>Immutable, so it is safe to hand the same instance to several threads
 * when a message is delivered to more than one recipient.</p>
 *
 * @param messageId server-generated unique id
 * @param type      {@link MessageType#CHAT} or {@link MessageType#PRIVATE}
 * @param sender    display name of the author, always resolved server-side
 * @param recipient display name of the target; {@code null} for public messages
 * @param content   the message body
 * @param timestamp server-side creation time
 */
public record ChatMessage(
        String messageId,
        MessageType type,
        String sender,
        String recipient,
        String content,
        Instant timestamp) {

    /** Maximum body length, mirrored by {@code @Size} on the message DTOs. */
    public static final int MAX_CONTENT_LENGTH = 2000;

    public ChatMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender must not be blank");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("timestamp must not be null");
        }
        if (type == MessageType.PRIVATE && (recipient == null || recipient.isBlank())) {
            throw new IllegalArgumentException("recipient is required for a private message");
        }
    }

    /** A public message addressed to everyone. */
    public static ChatMessage chat(String messageId, String sender, String content, Instant timestamp) {
        return new ChatMessage(messageId, MessageType.CHAT, sender, null, content, timestamp);
    }

    /** A message addressed to a single user. */
    public static ChatMessage privateMessage(
            String messageId, String sender, String recipient, String content, Instant timestamp) {
        return new ChatMessage(messageId, MessageType.PRIVATE, sender, recipient, content, timestamp);
    }
}
