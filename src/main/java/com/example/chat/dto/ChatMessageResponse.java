package com.example.chat.dto;

import com.example.chat.domain.ChatMessage;
import com.example.chat.domain.MessageType;

import java.time.Instant;

/**
 * Outbound message payload published on {@code /topic/public} (for
 * {@link MessageType#CHAT}) and on {@code /user/queue/private} (for
 * {@link MessageType#PRIVATE}).
 *
 * <p>Wire shape:</p>
 * <pre>
 * {
 *   "messageId": "0f2c...",           // server-generated, unique
 *   "type":      "CHAT" | "PRIVATE",  // never a client-supplied value
 *   "sender":    "alice",             // server-resolved, not from the payload
 *   "recipient": "bob" | null,        // set for PRIVATE, null for CHAT
 *   "content":   "hello",
 *   "timestamp": "2026-10-19T10:15:30.123Z"
 * }
 * </pre>
 *
 * <p>Kept separate from the {@link ChatMessage} domain type so that domain
 * changes do not silently alter the browser contract.</p>
 */
public record ChatMessageResponse(
        String messageId,
        MessageType type,
        String sender,
        String recipient,
        String content,
        Instant timestamp) {

    /** Maps a domain message onto its wire representation. */
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.messageId(),
                message.type(),
                message.sender(),
                message.recipient(),
                message.content(),
                message.timestamp());
    }
}
