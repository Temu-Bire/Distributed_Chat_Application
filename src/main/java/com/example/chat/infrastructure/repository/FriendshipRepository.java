package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.FriendshipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FriendshipRepository extends JpaRepository<FriendshipEntity, UUID> {

    List<FriendshipEntity> findByUserId(UUID userId);

    Optional<FriendshipEntity> findByUserIdAndFriendId(UUID userId, UUID friendId);

    boolean existsByUserIdAndFriendId(UUID userId, UUID friendId);

    void deleteByUserIdAndFriendId(UUID userId, UUID friendId);
}
