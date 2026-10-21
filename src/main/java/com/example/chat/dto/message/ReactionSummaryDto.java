package com.example.chat.dto.message;

/**
 * Aggregated emoji reaction on a message: one emoji and how many users chose it.
 */
public record ReactionSummaryDto(
    String emoji,
    long count
) {}
