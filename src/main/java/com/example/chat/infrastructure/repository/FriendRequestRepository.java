package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.FriendRequestEntity;
import com.example.chat.domain.entity.FriendRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequestEntity, UUID> {

    List<FriendRequestEntity> findByReceiverIdAndStatusOrderByCreatedAtDesc(UUID receiverId, FriendRequestStatus status);

    List<FriendRequestEntity> findBySenderIdAndStatusOrderByCreatedAtDesc(UUID senderId, FriendRequestStatus status);

    @Query("SELECT r FROM FriendRequestEntity r WHERE ((r.senderId = :u1 AND r.receiverId = :u2) OR (r.senderId = :u2 AND r.receiverId = :u1)) AND r.status = :status")
    Optional<FriendRequestEntity> findPendingBetween(@Param("u1") UUID u1, @Param("u2") UUID u2, @Param("status") FriendRequestStatus status);

    @Query("SELECT r FROM FriendRequestEntity r WHERE r.senderId = :senderId AND r.receiverId = :receiverId ORDER BY r.createdAt DESC")
    List<FriendRequestEntity> findLatestBetween(@Param("senderId") UUID senderId, @Param("receiverId") UUID receiverId);
}
