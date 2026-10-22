package com.example.chat.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversation_members", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"conversation_id", "user_id"})
})
public class ConversationMemberEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "last_read_message_id")
    private UUID lastReadMessageId;

    private Instant lastReadAt;

    @Column(nullable = false)
    private boolean archived = false;

    /**
     * Set when this member cleared the chat for themselves: they see no message
     * created at or before this instant, and nobody else's copy changes.
     */
    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    public ConversationMemberEntity() {}

    public ConversationMemberEntity(UUID conversationId, UUID userId, MemberRole role) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public MemberRole getRole() { return role; }
    public void setRole(MemberRole role) { this.role = role; }

    public UUID getLastReadMessageId() { return lastReadMessageId; }
    public void setLastReadMessageId(UUID lastReadMessageId) { this.lastReadMessageId = lastReadMessageId; }

    public Instant getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(Instant lastReadAt) { this.lastReadAt = lastReadAt; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }

    public Instant getClearedAt() { return clearedAt; }
    public void setClearedAt(Instant clearedAt) { this.clearedAt = clearedAt; }

    /** The oldest message this member may see; the epoch when the chat was never cleared. */
    public Instant historyCutoff() {
        return clearedAt == null ? Instant.EPOCH : clearedAt;
    }

    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
}
