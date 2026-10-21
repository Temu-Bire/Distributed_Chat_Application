package com.example.chat.application;

import com.example.chat.domain.entity.ConversationEntity;
import com.example.chat.domain.entity.ConversationMemberEntity;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.domain.entity.MessageEntity;
import com.example.chat.domain.entity.MessageReactionEntity;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.dto.message.ReactionSummaryDto;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.ConversationRepository;
import com.example.chat.infrastructure.repository.MessageReactionRepository;
import com.example.chat.infrastructure.repository.MessageRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PersistentMessageService {

    private static final int REPLY_PREVIEW_LENGTH = 120;

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageReactionRepository reactionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<DistributedMessageRelay> relayProvider;

    public PersistentMessageService(MessageRepository messageRepository,
                                  ConversationRepository conversationRepository,
                                  ConversationMemberRepository memberRepository,
                                  MessageReactionRepository reactionRepository,
                                  SimpMessagingTemplate messagingTemplate,
                                  ObjectProvider<DistributedMessageRelay> relayProvider) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.reactionRepository = reactionRepository;
        this.messagingTemplate = messagingTemplate;
        this.relayProvider = relayProvider;
    }

    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content) {
        return sendMessage(conversationId, senderId, senderUsername, content, null, null);
    }

    @Transactional
    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content,
                                                 UUID replyToId, String clientMessageId) {
        if (content == null || content.trim().isEmpty()) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message content cannot be blank");
        }
        if (content.length() > 2000) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message must be at most 2000 characters");
        }

        String normalizedClientId = clientMessageId == null || clientMessageId.isBlank()
            ? null
            : clientMessageId.trim();

        // Retry of an already-persisted send: return the original, never a duplicate.
        if (normalizedClientId != null) {
            Optional<MessageEntity> existing = messageRepository
                .findByConversationIdAndClientMessageId(conversationId, normalizedClientId);
            if (existing.isPresent()) {
                return enrich(existing.get());
            }
        }

        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(conversationId, senderId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        if (replyToId != null) {
            MessageEntity target = messageRepository.findById(replyToId)
                .orElseThrow(() -> new ChatException(ChatException.MESSAGE_NOT_FOUND, "The message you replied to no longer exists"));
            if (!target.getConversationId().equals(conversationId)) {
                throw new ChatException(ChatException.INVALID_COMMAND, "Cannot reply to a message from another conversation");
            }
        }

        MessageEntity message = new MessageEntity(conversationId, senderId, senderUsername, content.trim(), replyToId);
        message.setClientMessageId(normalizedClientId);
        message = messageRepository.save(message);

        // Update conversation summary
        conv.setLastMessageText(message.getContent());
        conv.setLastMessageAt(message.getCreatedAt());
        conv.setLastMessageSender(senderUsername);
        conv.setUpdatedAt(Instant.now());
        conversationRepository.save(conv);

        // Update sender's lastReadAt
        member.setLastReadAt(message.getCreatedAt());
        member.setLastReadMessageId(message.getId());
        memberRepository.save(member);

        PersistentMessageResponse response = enrich(message);
        broadcastConversationEvent(conversationId, "NEW_MESSAGE", response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<PersistentMessageResponse> getMessages(UUID conversationId, UUID currentUserId, int page, int size) {
        memberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        int boundedPage = Math.max(0, page);
        int boundedSize = Math.clamp(size, 1, 100);

        Page<MessageEntity> pageData = messageRepository.findRecentMessages(conversationId, PageRequest.of(boundedPage, boundedSize));
        List<MessageEntity> list = new ArrayList<>(pageData.getContent());
        Collections.reverse(list);
        return list.stream().map(this::enrich).toList();
    }

    @Transactional
    public PersistentMessageResponse editMessage(UUID messageId, UUID currentUserId, String newContent) {
        if (newContent == null || newContent.trim().isEmpty()) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message content cannot be blank");
        }
        if (newContent.length() > 2000) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message must be at most 2000 characters");
        }

        MessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ChatException(ChatException.INVALID_COMMAND, "Message not found"));

        if (message.isDeleted()) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot edit a deleted message");
        }

        if (!message.getSenderId().equals(currentUserId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You can only edit your own messages");
        }

        message.setContent(newContent.trim());
        message.setEdited(true);
        message.setEditedAt(Instant.now());
        message = messageRepository.save(message);

        PersistentMessageResponse response = enrich(message);
        broadcastConversationEvent(message.getConversationId(), "MESSAGE_EDITED", response);
        return response;
    }

    @Transactional
    public void deleteMessage(UUID messageId, UUID currentUserId) {
        MessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ChatException(ChatException.INVALID_COMMAND, "Message not found"));

        if (message.isDeleted()) {
            return;
        }

        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(message.getConversationId(), currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        boolean isSender = message.getSenderId().equals(currentUserId);
        boolean isManager = member.getRole() == MemberRole.OWNER || member.getRole() == MemberRole.MODERATOR;

        if (!isSender && !isManager) {
            throw new ChatException(ChatException.FORBIDDEN, "You are not allowed to delete this message");
        }

        message.setDeleted(true);
        message.setContent("This message was deleted.");
        message.setReplyToId(null);
        messageRepository.save(message);
        reactionRepository.deleteByMessageId(messageId);

        Map<String, Object> payload = Map.of(
            "messageId", messageId,
            "conversationId", message.getConversationId(),
            "event", "MESSAGE_DELETED"
        );
        broadcastConversationEvent(message.getConversationId(), "MESSAGE_DELETED", payload);
    }

    /**
     * Adds the emoji if the user has not reacted with it yet, removes it otherwise.
     * Returns the message with its updated reaction summary.
     */
    @Transactional
    public PersistentMessageResponse toggleReaction(UUID messageId, UUID currentUserId, String emoji) {
        if (emoji == null || emoji.isBlank() || emoji.trim().length() > 16) {
            throw new ChatException(ChatException.INVALID_REACTION, "Reaction must be a short emoji");
        }
        String normalizedEmoji = emoji.trim();

        MessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new ChatException(ChatException.MESSAGE_NOT_FOUND, "Message not found"));

        if (message.isDeleted()) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot react to a deleted message");
        }

        memberRepository.findByConversationIdAndUserId(message.getConversationId(), currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        Optional<MessageReactionEntity> existing =
            reactionRepository.findByMessageIdAndUserIdAndEmoji(messageId, currentUserId, normalizedEmoji);
        if (existing.isPresent()) {
            reactionRepository.delete(existing.get());
        } else {
            reactionRepository.save(new MessageReactionEntity(messageId, currentUserId, normalizedEmoji));
        }
        reactionRepository.flush();

        PersistentMessageResponse response = enrich(message);
        broadcastConversationEvent(message.getConversationId(), "MESSAGE_REACTED", response);
        return response;
    }

    /** Message + reply preview + aggregated reactions, in one view. */
    private PersistentMessageResponse enrich(MessageEntity message) {
        String replyPreview = null;
        if (message.getReplyToId() != null) {
            replyPreview = messageRepository.findById(message.getReplyToId())
                .map(target -> target.isDeleted()
                    ? "This message was deleted."
                    : preview(target.getContent()))
                .orElse(null);
        }
        return new PersistentMessageResponse(
            message.getId(),
            message.getConversationId(),
            message.getSenderId(),
            message.getSenderUsername(),
            message.getContent(),
            message.isEdited(),
            message.isDeleted(),
            message.getReplyToId(),
            replyPreview,
            reactionSummaries(List.of(message.getId())).getOrDefault(message.getId(), List.of()),
            message.getCreatedAt(),
            message.getEditedAt()
        );
    }

    private Map<UUID, List<ReactionSummaryDto>> reactionSummaries(List<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Map<String, Long>> grouped = reactionRepository.findByMessageIdIn(messageIds).stream()
            .collect(Collectors.groupingBy(MessageReactionEntity::getMessageId,
                Collectors.groupingBy(MessageReactionEntity::getEmoji,
                    LinkedHashMap::new, Collectors.counting())));
        Map<UUID, List<ReactionSummaryDto>> result = new HashMap<>();
        grouped.forEach((messageId, byEmoji) -> result.put(messageId, byEmoji.entrySet().stream()
            .map(entry -> new ReactionSummaryDto(entry.getKey(), entry.getValue()))
            .toList()));
        return result;
    }

    private static String preview(String content) {
        return content.length() <= REPLY_PREVIEW_LENGTH ? content : content.substring(0, REPLY_PREVIEW_LENGTH) + "...";
    }

    private void broadcastConversationEvent(UUID conversationId, String eventType, Object payload) {
        String destination = MessageRouter.conversationTopic(conversationId);
        Map<String, Object> frame = Map.of(
            "event", eventType,
            "conversationId", conversationId,
            "data", payload,
            "timestamp", Instant.now().toString()
        );
        try {
            messagingTemplate.convertAndSend(destination, frame);
        } catch (Exception e) {
            // Log warning; in distributed mode or without listeners, don't fail transaction
        }

        try {
            DistributedMessageRelay relay = relayProvider != null ? relayProvider.getIfAvailable() : null;
            if (relay != null) {
                relay.publishCrossInstance(destination, frame);
            }
        } catch (Exception e) {
            // Redis unavailable or serialization error; don't fail local transaction
        }
    }
}
