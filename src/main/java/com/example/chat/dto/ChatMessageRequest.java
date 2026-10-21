package com.example.chat.dto;

import com.example.chat.dto.message.AttachmentRefDto;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound payload for sending a message into a persisted conversation.
 *
 * <p>Bean Validation rejects oversized bodies before the message reaches the
 * application layer; blankness is checked there instead, because an
 * attachment-only message is legitimate.</p>
 */
public record ChatMessageRequest(

        @Size(max = ChatMessageRequest.MAX_CONTENT_LENGTH,
                message = "Messages must be at most " + ChatMessageRequest.MAX_CONTENT_LENGTH + " characters")
        String content,

        UUID replyToId,

        @Size(max = 64, message = "Client message id must be at most 64 characters")
        String clientMessageId,

        AttachmentRefDto attachment) {

    /** Maximum body length, mirrored by the persisted message service. */
    public static final int MAX_CONTENT_LENGTH = 2000;

    public ChatMessageRequest(String content) {
        this(content, null, null, null);
    }
}
