package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.MessageReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReactionEntity, UUID> {

    List<MessageReactionEntity> findByMessageId(UUID messageId);

    List<MessageReactionEntity> findByMessageIdIn(List<UUID> messageIds);

    List<MessageReactionEntity> findByMessageIdAndUserId(UUID messageId, UUID userId);

    void deleteByMessageId(UUID messageId);
}
