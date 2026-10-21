package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.ConversationEntity;
import com.example.chat.domain.entity.ConversationType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    /**
     * Locks the group row for the rest of the transaction. Every path that
     * can insert a membership row takes this lock first, which turns the
     * check-then-insert against {@code uq_conv_member} into a serialized
     * critical section instead of a race that surfaces as a constraint
     * violation mid-commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id")
    Optional<ConversationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT c FROM ConversationEntity c JOIN ConversationMemberEntity cm ON c.id = cm.conversationId " +
           "WHERE cm.userId = :userId AND cm.archived = false ORDER BY c.updatedAt DESC")
    List<ConversationEntity> findActiveConversationsForUser(@Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c " +
           "JOIN ConversationMemberEntity cm1 ON c.id = cm1.conversationId " +
           "JOIN ConversationMemberEntity cm2 ON c.id = cm2.conversationId " +
           "WHERE c.type = 'DIRECT' AND cm1.userId = :user1Id AND cm2.userId = :user2Id")
    Optional<ConversationEntity> findDirectConversationBetween(@Param("user1Id") UUID user1Id, @Param("user2Id") UUID user2Id);

    Optional<ConversationEntity> findFirstByType(ConversationType type);

    @Query("SELECT c FROM ConversationEntity c WHERE c.type = 'GROUP' ORDER BY c.createdAt DESC")
    Page<ConversationEntity> findGroups(Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.type = 'GROUP' AND LOWER(c.title) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY c.createdAt DESC")
    Page<ConversationEntity> findGroupsByTitleContaining(@Param("query") String query, Pageable pageable);
}
