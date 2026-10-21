package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.GroupJoinRequestEntity;
import com.example.chat.domain.entity.GroupJoinRequestStatus;
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
public interface GroupJoinRequestRepository extends JpaRepository<GroupJoinRequestEntity, UUID> {

    /** Row lock: two admins accepting the same request cannot both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GroupJoinRequestEntity r WHERE r.id = :id")
    Optional<GroupJoinRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    List<GroupJoinRequestEntity> findByGroupId(UUID groupId);

    List<GroupJoinRequestEntity> findByGroupIdAndStatusOrderByCreatedAtDesc(UUID groupId, GroupJoinRequestStatus status);

    List<GroupJoinRequestEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, GroupJoinRequestStatus status);

    Optional<GroupJoinRequestEntity> findByGroupIdAndUserId(UUID groupId, UUID userId);

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

    void deleteByGroupIdAndUserId(UUID groupId, UUID userId);
}
