package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.exception.ChatException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ReadReceiptTest {

    @Autowired private PersistentMessageService messageService;
    @Autowired private ConversationService conversationService;
    @Autowired private AuthService authService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("rr_alice", "rr_alice@test.com", "pass123", "Alice RR"), null);
        bob = authService.register(new RegisterRequest("rr_bob", "rr_bob@test.com", "pass123", "Bob RR"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    @Test
    @DisplayName("unread message shows zero readers to the sender")
    void freshMessageUnread() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Anybody there?");

        List<PersistentMessageResponse> asSeenByAlice = messageService.getMessages(direct.id(), alice.id(), 0, 10);
        assertThat(asSeenByAlice.get(0).readByCount()).isZero();
    }

    @Test
    @DisplayName("marking read makes earlier messages counted as read for every member view")
    void markReadAdvancesReceipts() {
        PersistentMessageResponse msg = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Read me");

        messageService.markMessagesRead(direct.id(), bob.id());

        List<PersistentMessageResponse> aliceView = messageService.getMessages(direct.id(), alice.id(), 0, 10);
        assertThat(aliceView.get(0).readByCount()).isEqualTo(1);

        // readByCount is relative to the sender, so every viewer sees the same number.
        List<PersistentMessageResponse> bobView = messageService.getMessages(direct.id(), bob.id(), 0, 10);
        assertThat(bobView.get(0).readByCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("group read count grows with each reader")
    void groupReadCount() {
        UserProfileResponse carol = authService.register(new RegisterRequest("rr_carol", "rr_carol@test.com", "pass123", "Carol RR"), null);
        ConversationSummaryResponse gym = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Read receipts", null, null, List.of(bob.id(), carol.id())));
        PersistentMessageResponse msg = messageService.sendMessage(gym.id(), alice.id(), alice.username(), "Everyone pair up");

        messageService.markMessagesRead(gym.id(), bob.id());
        assertThat(messageService.getMessages(gym.id(), alice.id(), 0, 10).get(0).readByCount()).isEqualTo(1);

        messageService.markMessagesRead(gym.id(), carol.id());
        assertThat(messageService.getMessages(gym.id(), alice.id(), 0, 10).get(0).readByCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("messages sent after a read watermark are not marked read")
    void messagesAfterWatermarkStayUnread() {
        messageService.markMessagesRead(direct.id(), bob.id());
        PersistentMessageResponse later = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "Sent after bob read");

        List<PersistentMessageResponse> view = messageService.getMessages(direct.id(), alice.id(), 0, 10);
        assertThat(view.get(view.size() - 1).id()).isEqualTo(later.id());
        assertThat(view.get(view.size() - 1).readByCount()).isZero();
    }

    @Test
    @DisplayName("non-members cannot mark a conversation read")
    void markReadRequiresMembership() {
        UserProfileResponse carol = authService.register(new RegisterRequest("rr_carol2", "rr_carol2@test.com", "pass123", "Carol"), null);

        assertThatThrownBy(() -> messageService.markMessagesRead(direct.id(), carol.id()))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);
    }
}
