package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.message.AttachmentRefDto;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.exception.ChatException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MessageAttachmentTest {

    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13
    };

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;
    @Autowired private UploadService uploadService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("att_alice", "att_alice@test.com", "pass123", "Alice Att"), null);
        bob = authService.register(new RegisterRequest("att_bob", "att_bob@test.com", "pass123", "Bob Att"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    private AttachmentRefDto mintPng() {
        UploadService.StoredUpload stored = uploadService.store(
            new MockMultipartFile("file", "diagram.png", "image/png", PNG), "media", "diagram.png");
        return new AttachmentRefDto(stored.url());
    }

    @Test
    @DisplayName("an uploaded image attaches to the message and survives history reload")
    void attachesServerMintedFile() {
        AttachmentRefDto attachment = mintPng();

        PersistentMessageResponse msg = messageService.sendMessage(
            direct.id(), alice.id(), alice.username(), "Look at this", null, null, null, attachment);

        assertThat(msg.attachment()).isNotNull();
        assertThat(msg.attachment().name()).isEqualTo("diagram.png");
        assertThat(msg.attachment().type()).isEqualTo("image/png");
        assertThat(msg.attachment().size()).isEqualTo(PNG.length);

        List<PersistentMessageResponse> history = messageService.getMessages(direct.id(), bob.id(), 0, 10);
        assertThat(history.get(0).attachment().url()).isEqualTo(attachment.url());
    }

    @Test
    @DisplayName("an attachment alone is a complete message")
    void attachmentOnlyMessage() {
        PersistentMessageResponse msg = messageService.sendMessage(
            direct.id(), alice.id(), alice.username(), "   ", null, null, null, mintPng());

        assertThat(msg.content()).isEmpty();
        assertThat(msg.attachment()).isNotNull();

        ConversationSummaryResponse summary = conversationService.getConversationDetails(direct.id(), bob.id());
        assertThat(summary.lastMessageText()).isEqualTo("[Attachment]");
    }

    @Test
    @DisplayName("a client cannot attach an arbitrary or remote url")
    void rejectsForeignUrls() {
        assertThatThrownBy(() -> messageService.sendMessage(direct.id(), alice.id(), alice.username(),
            "sneaky", null, null, null, new AttachmentRefDto("https://evil.example/track.png")))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_UPLOAD);

        assertThatThrownBy(() -> messageService.sendMessage(direct.id(), alice.id(), alice.username(),
            "sneaky", null, null, null, new AttachmentRefDto("/uploads/../../data/chatsphere.mv.db")))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_UPLOAD);
    }

    @Test
    @DisplayName("a blank message without an attachment is still rejected")
    void blankTextWithoutAttachmentRejected() {
        assertThatThrownBy(() -> messageService.sendMessage(direct.id(), alice.id(), alice.username(),
            "  ", null, null, null, null))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.INVALID_MESSAGE);
    }
}
