package com.example.chat.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_blocks", uniqueConstraints = {
    @UniqueConstraint(name = "uq_group_block", columnNames = {"group_id", "user_id"})
}, indexes = {
    @Index(name = "idx_block_group", columnList = "group_id, user_id")
})
public class GroupBlockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "blocked_by", nullable = false)
    private UUID blockedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public GroupBlockEntity() {}

    public GroupBlockEntity(UUID groupId, UUID userId, UUID blockedBy) {
        this.groupId = groupId;
        this.userId = userId;
        this.blockedBy = blockedBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public UUID getBlockedBy() { return blockedBy; }
    public void setBlockedBy(UUID blockedBy) { this.blockedBy = blockedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
