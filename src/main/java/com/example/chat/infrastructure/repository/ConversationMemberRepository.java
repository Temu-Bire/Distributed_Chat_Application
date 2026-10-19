package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.ConversationMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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

    void deleteByConversationIdAndUserId(UUID conversationId, UUID userId);

    void deleteByConversationId(UUID conversationId);
}
