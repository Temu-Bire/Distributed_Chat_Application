package com.example.chat.dto.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * One live notification, delivered to its owner's private topic.
 *
 * <p>Notifications are deliberately <em>not</em> stored: they are the pointer
 * that says "something happened", and the content they point at is already
 * persisted and readable over REST. A reader that was offline therefore sees
 * the real state (unread counts, pending requests) when it next loads the
 * app, without this service keeping a second copy of the truth that could
 * drift.</p>
 */
public record NotificationDto(
    String type,
    String category,
    UUID actorId,
    String actorUsername,
    UUID conversationId,
    UUID messageId,
    String title,
    String body,
    Instant createdAt
) {
}
