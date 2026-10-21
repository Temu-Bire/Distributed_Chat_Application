package com.example.chat.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_invite_links", uniqueConstraints = {
    @UniqueConstraint(name = "uq_invite_token", columnNames = "token")
})
public class GroupInviteLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public GroupInviteLinkEntity() {}

    public GroupInviteLinkEntity(UUID groupId, UUID createdBy, String token, Instant expiresAt, Integer maxUses) {
        this.groupId = groupId;
        this.createdBy = createdBy;
        this.token = token;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
        this.createdAt = Instant.now();
    }

    /**
     * Whether this link still admits members at {@code now}. The instant is a
     * parameter rather than {@code Instant.now()} so expiry is checkable
     * without waiting for a clock to roll over.
     */
    public boolean isActiveAt(Instant now) {
        if (revoked) return false;
        if (expiresAt != null && !now.isBefore(expiresAt)) return false;
        return maxUses == null || usedCount < maxUses;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }

    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public Instant getCreatedAt() { return createdAt; }
}
