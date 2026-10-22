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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    /** Row lock guarding the check-then-insert of {@code uq_message_reaction}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MessageEntity m WHERE m.id = :id")
    Optional<MessageEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Every read that shows history to a member passes their own cutoff, so a
     * chat cleared "for me" reads as empty for me and stays whole for everyone
     * else. Callers pass {@link Instant#EPOCH} for a member who never cleared it.
     */
    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.createdAt > :after ORDER BY m.createdAt DESC")
    Page<MessageEntity> findRecentMessages(@Param("convId") UUID conversationId, @Param("after") Instant after, Pageable pageable);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.pinned = true AND m.deleted = false " +
           "AND m.createdAt > :after ORDER BY m.pinnedAt DESC")
    Page<MessageEntity> findPinned(@Param("convId") UUID conversationId, @Param("after") Instant after, Pageable pageable);

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId AND m.deleted = false AND m.createdAt > :after " +
           "AND UPPER(m.content) LIKE UPPER(CONCAT('%', :query, '%')) ESCAPE '\\' ORDER BY m.createdAt DESC")
    Page<MessageEntity> searchContent(@Param("convId") UUID conversationId, @Param("after") Instant after,
                                      @Param("query") String query, Pageable pageable);

    Optional<MessageEntity> findByConversationIdAndClientMessageId(UUID conversationId, String clientMessageId);

    Optional<MessageEntity> findFirstByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :convId AND m.deleted = false AND m.createdAt > :after AND m.createdAt > (SELECT COALESCE(cm.lastReadAt, cm.joinedAt) FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId = :userId)")
    long countUnreadMessages(@Param("convId") UUID conversationId, @Param("userId") UUID userId, @Param("after") Instant after);

    void deleteByConversationId(UUID conversationId);
}
