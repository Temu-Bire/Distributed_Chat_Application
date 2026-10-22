package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.ConversationMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationMemberRepository extends JpaRepository<ConversationMemberEntity, UUID> {

    List<ConversationMemberEntity> findByConversationId(UUID conversationId);

    List<ConversationMemberEntity> findByUserId(UUID userId);

    Optional<ConversationMemberEntity> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    boolean existsByConversationIdAndUserId(UUID conversationId, UUID userId);

    long countByConversationId(UUID conversationId);

    void deleteByConversationIdAndUserId(UUID conversationId, UUID userId);

    void deleteByConversationId(UUID conversationId);

    /** Groups both accounts belong to. Archived rows still count: the group is shared either way. */
    @Query("SELECT COUNT(DISTINCT a.conversationId) FROM ConversationMemberEntity a, " +
           "ConversationMemberEntity b, ConversationEntity c " +
           "WHERE c.id = a.conversationId AND c.type = 'GROUP' " +
           "AND a.userId = :viewerId AND b.userId = :targetId AND b.conversationId = a.conversationId")
    long countSharedGroups(@Param("viewerId") UUID viewerId, @Param("targetId") UUID targetId);
}
