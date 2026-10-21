package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    @Query("SELECT m FROM MessageEntity m WHERE m.conversationId = :convId ORDER BY m.createdAt DESC")
    Page<MessageEntity> findRecentMessages(@Param("convId") UUID conversationId, Pageable pageable);

    Optional<MessageEntity> findByConversationIdAndClientMessageId(UUID conversationId, String clientMessageId);

    @Query("SELECT COUNT(m) FROM MessageEntity m WHERE m.conversationId = :convId AND m.deleted = false AND m.createdAt > (SELECT COALESCE(cm.lastReadAt, cm.joinedAt) FROM ConversationMemberEntity cm WHERE cm.conversationId = :convId AND cm.userId = :userId)")
    long countUnreadMessages(@Param("convId") UUID conversationId, @Param("userId") UUID userId);

    void deleteByConversationId(UUID conversationId);
}
