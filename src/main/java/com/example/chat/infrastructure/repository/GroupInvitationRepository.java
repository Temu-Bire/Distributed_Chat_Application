package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.GroupInvitationEntity;
import com.example.chat.domain.entity.GroupInvitationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupInvitationRepository extends JpaRepository<GroupInvitationEntity, UUID> {

    /** Row lock: an invitation accepted twice concurrently is accepted once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM GroupInvitationEntity i WHERE i.id = :id")
    Optional<GroupInvitationEntity> findByIdForUpdate(@Param("id") UUID id);

    List<GroupInvitationEntity> findByInvitedUserIdAndStatusOrderByCreatedAtDesc(UUID invitedUserId, GroupInvitationStatus status);

    List<GroupInvitationEntity> findByGroupIdAndStatusOrderByCreatedAtDesc(UUID groupId, GroupInvitationStatus status);

    Optional<GroupInvitationEntity> findByGroupIdAndInvitedUserId(UUID groupId, UUID invitedUserId);

    boolean existsByGroupIdAndInvitedUserId(UUID groupId, UUID invitedUserId);

    void deleteByGroupIdAndInvitedUserId(UUID groupId, UUID invitedUserId);
}
