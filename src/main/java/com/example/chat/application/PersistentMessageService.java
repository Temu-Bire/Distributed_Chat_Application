package com.example.chat.application;

import com.example.chat.domain.entity.ConversationEntity;
import com.example.chat.domain.entity.ConversationMemberEntity;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.domain.entity.MessageEntity;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.ConversationRepository;
import com.example.chat.infrastructure.repository.MessageRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class PersistentMessageService {

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<DistributedMessageRelay> relayProvider;

    public PersistentMessageService(MessageRepository messageRepository,
                                  ConversationRepository conversationRepository,
                                  ConversationMemberRepository memberRepository,
                                  SimpMessagingTemplate messagingTemplate,
                                  ObjectProvider<DistributedMessageRelay> relayProvider) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.messagingTemplate = messagingTemplate;
        this.relayProvider = relayProvider;
    }

    @Transactional
    public PersistentMessageResponse sendMessage(UUID conversationId, UUID senderId, String senderUsername, String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message content cannot be blank");
        }
        if (content.length() > 2000) {
            throw new ChatException(ChatException.INVALID_MESSAGE, "Message must be at most 2000 characters");
        }

        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(conversationId, senderId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));

        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        MessageEntity message = new MessageEntity(conversationId, senderId, senderUsername, content.trim());
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

        PersistentMessageResponse response = PersistentMessageResponse.from(message);
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
        // Return in chronological order
        List<MessageEntity> list = new ArrayList<>(pageData.getContent());
        Collections.reverse(list);
        return list.stream().map(PersistentMessageResponse::from).toList();
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

        PersistentMessageResponse response = PersistentMessageResponse.from(message);
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
        boolean isOwner = member.getRole() == MemberRole.OWNER;

        if (!isSender && !isOwner) {
            throw new ChatException(ChatException.FORBIDDEN, "You are not allowed to delete this message");
        }

        message.setDeleted(true);
        message.setContent("This message was deleted.");
        messageRepository.save(message);

        Map<String, Object> payload = Map.of(
            "messageId", messageId,
            "conversationId", message.getConversationId(),
            "event", "MESSAGE_DELETED"
        );
        broadcastConversationEvent(message.getConversationId(), "MESSAGE_DELETED", payload);
    }

    private void broadcastConversationEvent(UUID conversationId, String eventType, Object payload) {
        String destination = "/topic/conversations/" + conversationId;
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
