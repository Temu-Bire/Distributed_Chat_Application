package com.example.chat.application;

import com.example.chat.domain.entity.ConversationEntity;
import com.example.chat.domain.entity.ConversationMemberEntity;
import com.example.chat.domain.entity.ConversationType;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.domain.entity.MessageEntity;
import com.example.chat.domain.entity.MessageReactionEntity;
import com.example.chat.dto.message.AttachmentRefDto;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.dto.message.ReactionSummaryDto;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import com.example.chat.security.RateLimiter;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final RateLimiter rateLimiter;
    private final UploadService uploadService;

    public PersistentMessageService(MessageRepository messageRepository,
                                  ConversationRepository conversationRepository,
                                  ConversationMemberRepository memberRepository,
                                  MessageReactionRepository reactionRepository,
                                  SimpMessagingTemplate messagingTemplate,
                                  ObjectProvider<DistributedMessageRelay> relayProvider,
                                  RateLimiter rateLimiter,
                                  UploadService uploadService) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.reactionRepository = reactionRepository;
        this.messagingTemplate = messagingTemplate;
        this.relayProvider = relayProvider;
        this.rateLimiter = rateLimiter;
        this.uploadService = uploadService;
    }

    @Transactional
    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content) {
        return sendMessage(conversationId, senderId, senderUsername, content, null, null);
    }

    @Transactional
    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content,
                                                 UUID replyToId, String clientMessageId) {
        return sendMessage(conversationId, senderId, senderUsername, content, replyToId, clientMessageId, null);
    }

    /**
     * Forward a copy of an existing message into another conversation. The
     * destination member check is implicit (sendMessage rejects non-members);
     * the source membership check is explicit because forwards must not leak
     * messages from a conversation the sender has left or never joined.
     */
    @Transactional
    public PersistentMessageResponse forwardMessage(UUID destinationId, UUID currentUserId, String username,
                                                    UUID sourceMessageId, String clientMessageId) {
        MessageEntity source = messageRepository.findById(sourceMessageId)
            .orElseThrow(() -> new ChatException(ChatException.MESSAGE_NOT_FOUND, "The message you tried to forward no longer exists"));

        if (source.isDeleted()) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot forward a deleted message");
        }
        memberRepository.findByConversationIdAndUserId(source.getConversationId(), currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of the source conversation"));
        if (source.getConversationId().equals(destinationId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "This message is already in that conversation");
        }

        return sendMessage(destinationId, currentUserId, username, source.getContent(),
            null, clientMessageId, sourceMessageId);
    }

    @Transactional
    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content,
                                                 UUID replyToId, String clientMessageId, UUID forwardedFromId) {
        return sendMessage(conversationId, senderId, senderUsername, content, replyToId, clientMessageId, forwardedFromId, null);
    }

    @Transactional
    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content,
                                                 UUID replyToId, String clientMessageId, UUID forwardedFromId,
                                                 AttachmentRefDto attachment) {
        UploadService.StoredUpload stored = null;
        if (attachment != null && attachment.url() != null && !attachment.url().isBlank()) {
            stored = uploadService.describe(attachment.url());
            if (stored == null) {
                throw new ChatException(ChatException.INVALID_UPLOAD, "That attachment was not uploaded to this server");
            }
        }
        boolean hasAttachment = stored != null;
        if (content == null || content.trim().isEmpty()) {
            if (!hasAttachment) {
                throw new ChatException(ChatException.INVALID_MESSAGE, "Message content cannot be blank");
            }
            content = "";
        }
        if (content.length() > 2000) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message must be at most 2000 characters");
        }

        String normalizedClientId = clientMessageId == null || clientMessageId.isBlank()
            ? null
            : clientMessageId.trim();

        // The conversation row is locked before anything is read or written:
        // two threads racing the same clientMessageId (a client retrying an
        // unacknowledged send) would otherwise both miss the pre-check and
        // one would die on uq_msg_client_id at commit time. With the lock,
        // the loser re-reads after the winner committed and gets the original
        // message back instead of an error. The same lock makes the summary
        // update below race-free: lastMessageText can never be lost.
        ConversationEntity conv = conversationRepository.findByIdForUpdate(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        // Retry of an already-persisted send: return the original, never a duplicate.
        if (normalizedClientId != null) {
            Optional<MessageEntity> existing = messageRepository
                .findByConversationIdAndClientMessageId(conversationId, normalizedClientId);
            if (existing.isPresent()) {
                return enrich(existing.get());
            }
        }

        // Only new persists consume the budget, so a client retrying an
        // unacknowledged send is never punished for the server's silence.
        RateLimiter.Decision budget = rateLimiter.forMessage(senderId);
        if (!budget.allowed()) {
            throw new ChatException(ChatException.RATE_LIMITED,
                "Sending too quickly - wait " + budget.retryAfterSeconds() + " seconds.");
        }

        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(conversationId, senderId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        if (replyToId != null) {
            MessageEntity target = messageRepository.findById(replyToId)
                .orElseThrow(() -> new ChatException(ChatException.MESSAGE_NOT_FOUND, "The message you replied to no longer exists"));
            if (!target.getConversationId().equals(conversationId)) {
                throw new ChatException(ChatException.INVALID_COMMAND, "Cannot reply to a message from another conversation");
            }
        }

        MessageEntity message = new MessageEntity(conversationId, senderId, senderUsername, content.trim(), replyToId);
        message.setClientMessageId(normalizedClientId);
        message.setForwardedFromId(forwardedFromId);
        if (stored != null) {
            message.setAttachmentUrl(stored.url());
            message.setAttachmentName(stored.name());
            message.setAttachmentType(stored.type());
            message.setAttachmentSize(stored.size());
        }
        message = messageRepository.save(message);

        // Update conversation summary
        conv.setLastMessageText(message.getContent().isEmpty() ? "[Attachment]" : message.getContent());
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

        // Lock the message: two readers tapping the same emoji simultaneously
        // must not both pass the "not reacted yet" check and collide on
        // uq_message_reaction while committing.
        MessageEntity message = messageRepository.findByIdForUpdate(messageId)
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

    /**
     * Pin or unpin a message. Groups restrict pinning to owner/admin/moderator
     * (a pinned banner is a shared surface, plain members must not edit it);
     * direct chats let either member pin. Unpinning follows the same rule as
     * deleting: sender or group manager.
     */
    @Transactional
    public PersistentMessageResponse setPinned(UUID messageId, UUID currentUserId, boolean pinned) {
        MessageEntity message = messageRepository.findByIdForUpdate(messageId)
            .orElseThrow(() -> new ChatException(ChatException.MESSAGE_NOT_FOUND, "Message not found"));

        if (message.isDeleted()) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot pin a deleted message");
        }

        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(message.getConversationId(), currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));
        ConversationEntity conv = conversationRepository.findById(message.getConversationId())
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        boolean isManager = member.getRole() == MemberRole.OWNER || member.getRole() == MemberRole.MODERATOR;
        boolean isSender = message.getSenderId().equals(currentUserId);
        if (conv.getType() == ConversationType.GROUP && pinned && !isManager) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins or moderators can pin messages");
        }
        if (conv.getType() == ConversationType.GROUP && !pinned && !isManager && !isSender) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins or moderators can unpin this message");
        }

        message.setPinned(pinned);
        message.setPinnedBy(pinned ? currentUserId : null);
        message.setPinnedAt(pinned ? Instant.now() : null);
        message = messageRepository.save(message);

        PersistentMessageResponse response = enrich(message);
        broadcastConversationEvent(message.getConversationId(), "MESSAGE_PINNED", response);
        return response;
    }

    @Transactional(readOnly = true)
    public List<PersistentMessageResponse> getPinnedMessages(UUID conversationId, UUID currentUserId) {
        memberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));
        List<MessageEntity> pinned = messageRepository
            .findPinned(conversationId, PageRequest.of(0, 20)).getContent();
        return pinned.stream().map(this::enrich).toList();
    }

    /**
     * Records that the reader has seen every message in the conversation and
     * tells the other members so their tick marks can update live.
     */
    @Transactional
    public void markMessagesRead(UUID conversationId, UUID currentUserId) {
        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        Instant readAt = Instant.now();
        messageRepository.findFirstByConversationIdOrderByCreatedAtDesc(conversationId)
            .ifPresent(latest -> member.setLastReadMessageId(latest.getId()));
        member.setLastReadAt(readAt);
        memberRepository.save(member);

        broadcastConversationEvent(conversationId, "READ_UPDATE", Map.of(
            "userId", currentUserId,
            "readAt", readAt
        ));
    }

    @Transactional(readOnly = true)
    public List<PersistentMessageResponse> searchMessages(UUID conversationId, UUID currentUserId, String query, int limit) {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }
        String trimmed = query.trim();
        if (trimmed.length() > 100) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Search query must be at most 100 characters");
        }
        memberRepository.findByConversationIdAndUserId(conversationId, currentUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));
        int boundedLimit = Math.clamp(limit, 1, 50);
        String escaped = trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<MessageEntity> found = messageRepository
            .searchContent(conversationId, escaped, PageRequest.of(0, boundedLimit)).getContent();
        return found.stream().map(this::enrich).toList();
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
        int readByCount = (int) memberRepository.findByConversationId(message.getConversationId()).stream()
            .filter(other -> !other.getUserId().equals(message.getSenderId()))
            .filter(other -> other.getLastReadAt() != null
                && !other.getLastReadAt().isBefore(message.getCreatedAt()))
            .count();
        return new PersistentMessageResponse(
            message.getId(),
            message.getConversationId(),
            message.getSenderId(),
            message.getSenderUsername(),
            message.getContent(),
            message.isEdited(),
            message.isDeleted(),
            message.isPinned(),
            readByCount,
            message.getForwardedFromId() != null,
            message.getReplyToId(),
            replyPreview,
            attachmentOf(message),
            reactionSummaries(List.of(message.getId())).getOrDefault(message.getId(), List.of()),
            message.getCreatedAt(),
            message.getEditedAt()
        );
    }

    private static com.example.chat.dto.message.AttachmentDto attachmentOf(MessageEntity message) {
        if (message.getAttachmentUrl() == null) {
            return null;
        }
        return new com.example.chat.dto.message.AttachmentDto(message.getAttachmentUrl(), message.getAttachmentName(),
            message.getAttachmentType(), message.getAttachmentSize() == null ? 0 : message.getAttachmentSize());
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

        // Only after the row is durably committed may the fan-out start:
        // a frame published inside the transaction would reach subscribers
        // with a message body that a rollback could still erase, and a client
        // that immediately fetched history would not find it.
        Runnable publish = () -> {
            try {
                messagingTemplate.convertAndSend(destination, frame);
            } catch (Exception e) {
                // Log warning; in distributed mode or without listeners, don't fail the committed send
            }

            try {
                DistributedMessageRelay relay = relayProvider != null ? relayProvider.getIfAvailable() : null;
                if (relay != null) {
                    relay.publishCrossInstance(destination, frame);
                }
            } catch (Exception e) {
                // Redis unavailable or serialization error; the local commit stands
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
