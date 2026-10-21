package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.GroupBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroupBlockRepository extends JpaRepository<GroupBlockEntity, UUID> {

    boolean existsByGroupIdAndUserId(UUID groupId, UUID userId);

    List<GroupBlockEntity> findByGroupId(UUID groupId);

    void deleteByGroupIdAndUserId(UUID groupId, UUID userId);
}
