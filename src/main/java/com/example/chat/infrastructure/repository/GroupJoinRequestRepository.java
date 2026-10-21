package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.GroupJoinRequestEntity;
import com.example.chat.domain.entity.GroupJoinRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupJoinRequestRepository extends JpaRepository<GroupJoinRequestEntity, UUID> {

    List<GroupJoinRequestEntity> findByGroupId(UUID groupId);

    List<GroupJoinRequestEntity> findByGroupIdAndStatusOrderByCreatedAtDesc(UUID groupId, GroupJoinRequestStatus status);

    List<GroupJoinRequestEntity> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, GroupJoinRequestStatus status);

    Optional<GroupJoinRequestEntity> findByGroupIdAndUserId(UUID groupId, UUID userId);

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

    void deleteByGroupIdAndUserId(UUID groupId, UUID userId);
}
