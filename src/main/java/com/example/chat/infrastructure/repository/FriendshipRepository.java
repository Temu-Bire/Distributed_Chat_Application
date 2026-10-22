package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.FriendshipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Accounts that appear in both friend lists. Friendships are stored in both
     * directions, so matching one row per side is enough - and neither party can
     * be counted for themselves, because nobody has a friendship row with self.
     */
    @Query("SELECT COUNT(f1.friendId) FROM FriendshipEntity f1, FriendshipEntity f2 " +
           "WHERE f1.userId = :viewerId AND f2.userId = :targetId AND f2.friendId = f1.friendId")
    long countMutualFriends(@Param("viewerId") UUID viewerId, @Param("targetId") UUID targetId);
}
