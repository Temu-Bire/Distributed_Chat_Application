package com.example.chat.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_invitations", uniqueConstraints = {
    @UniqueConstraint(name = "uq_group_invitation", columnNames = {"group_id", "invited_user_id"})
})
public class GroupInvitationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "inviter_id", nullable = false)
    private UUID inviterId;

    @Column(name = "invited_user_id", nullable = false)
    private UUID invitedUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GroupInvitationStatus status = GroupInvitationStatus.PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public GroupInvitationEntity() {}

    public GroupInvitationEntity(UUID groupId, UUID inviterId, UUID invitedUserId) {
        this.groupId = groupId;
        this.inviterId = inviterId;
        this.invitedUserId = invitedUserId;
        this.status = GroupInvitationStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public UUID getInviterId() {
        return inviterId;
    }

    public void setInviterId(UUID inviterId) {
        this.inviterId = inviterId;
    }

    public UUID getInvitedUserId() {
        return invitedUserId;
    }

    public void setInvitedUserId(UUID invitedUserId) {
        this.invitedUserId = invitedUserId;
    }

    public GroupInvitationStatus getStatus() {
        return status;
    }

    public void setStatus(GroupInvitationStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
