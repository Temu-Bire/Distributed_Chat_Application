package com.example.chat.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_msg_conv_created", columnList = "conversation_id, created_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_msg_client_id", columnNames = {"conversation_id", "client_message_id"})
})
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "sender_username", nullable = false, length = 50)
    private String senderUsername;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(nullable = false)
    private boolean edited = false;

    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "reply_to_id")
    private UUID replyToId;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private Instant editedAt;

    public MessageEntity() {}

    public MessageEntity(UUID conversationId, UUID senderId, String senderUsername, String content) {
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderUsername = senderUsername;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public MessageEntity(UUID conversationId, UUID senderId, String senderUsername, String content, UUID replyToId) {
        this(conversationId, senderId, senderUsername, content);
        this.replyToId = replyToId;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }

    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }

    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public UUID getReplyToId() { return replyToId; }
    public void setReplyToId(UUID replyToId) { this.replyToId = replyToId; }

    public String getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant editedAt) { this.editedAt = editedAt; }
}
