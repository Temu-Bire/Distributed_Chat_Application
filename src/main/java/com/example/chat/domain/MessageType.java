package com.example.chat.domain;

/**
 * Kind of chat message understood by the domain.
 *
 * <p>Join and leave notices are not chat messages — they are presence
 * events and travel as a {@code UserPresenceResponse} on {@code /topic/users}.
 * Keeping them out of this enum means {@code ChatMessage} never has to carry
 * a meaningless sender-only shape.</p>
 */
public enum MessageType {

    /** Addressed to every connected user; public. */
    CHAT,

    /** Addressed to exactly one connected user; never published publicly. */
    PRIVATE
}
