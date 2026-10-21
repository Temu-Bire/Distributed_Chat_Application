package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.MessageEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /** Row lock guarding the check-then-insert of {@code uq_message_reaction}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MessageEntity m WHERE m.id = :id")
    Optional<MessageEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId ORDER BY m.createdAt DESC")
    Page<MessageEntity> findRecentMessages(@Param("convId") UUID conversationId, Pageable pageable);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.pinned = true AND m.deleted = false ORDER BY m.pinnedAt DESC")
    Page<MessageEntity> findPinned(@Param("convId") UUID conversationId, Pageable pageable);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.deleted = false AND UPPER(m.content) LIKE UPPER(CONCAT('%', :query, '%')) ESCAPE '\\' ORDER BY m.createdAt DESC")
    Page<MessageEntity> searchContent(@Param("convId") UUID conversationId, @Param("query") String query, Pageable pageable);

    Optional<MessageEntity> findByConversationIdAndClientMessageId(UUID conversationId, String clientMessageId);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :convId AND m.deleted = false AND m.createdAt > (SELECT COALESCE(cm.lastReadAt, cm.joinedAt) FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId = :userId)")
    long countUnreadMessages(@Param("convId") UUID conversationId, @Param("userId") UUID userId);

    void deleteByConversationId(UUID conversationId);
}
