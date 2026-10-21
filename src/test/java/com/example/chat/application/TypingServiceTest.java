package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Transactional
class TypingServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationMemberRepository memberRepository;

    private final AtomicLong clockValue = new AtomicLong(1_000_000L);
    private SimpMessagingTemplate messaging;
    private TypingService typingService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private UserProfileResponse carol;
    private ConversationSummaryResponse directConv;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("typ_alice", "typ_alice@test.com", "pass123", "Alice T"), null);
        bob = authService.register(new RegisterRequest("typ_bob", "typ_bob@test.com", "pass123", "Bob T"), null);
        carol = authService.register(new RegisterRequest("typ_carol", "typ_carol@test.com", "pass123", "Carol T"), null);
        directConv = conversationService.getOrCreateDirect(alice.id(), bob.id());
        messaging = mock(SimpMessagingTemplate.class);
        typingService = new TypingService(memberRepository, messaging, null, clockValue::get);
    }

    @Test
    @DisplayName("a member's typing signal is broadcast as an ephemeral TYPING event")
    void memberSignalIsBroadcast() {
        typingService.notifyTyping(directConv.id(), alice.id(), alice.username(), true);

        ArgumentCaptor<Object> frameCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/conversations/" + directConv.id()), frameCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> frame = (Map<String, Object>) frameCaptor.getValue();
        assertThat(frame).containsEntry("event", "TYPING");
        assertThat(frame).containsEntry("conversationId", directConv.id());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) frame.get("data");
        assertThat(data)
            .containsEntry("username", "typ_alice")
            .containsEntry("typing", true);
    }

    @Test
    @DisplayName("a non-member cannot broadcast into a conversation it has no access to")
    void nonMemberIsRefused() {
        assertThatThrownBy(() -> typingService.notifyTyping(directConv.id(), carol.id(), carol.username(), true))
            .isInstanceOf(ChatException.class)
            .hasFieldOrPropertyWithValue("code", ChatException.NOT_MEMBER);

        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("repeated signals inside the throttle window fan out only once")
    void signalsAreThrottled() {
        for (int i = 0; i < 10; i++) {
            typingService.notifyTyping(directConv.id(), alice.id(), alice.username(), true);
            clockValue.addAndGet(100);
        }
        verify(messaging, times(1)).convertAndSend(anyString(), any(Object.class));

        clockValue.addAndGet(3000);
        typingService.notifyTyping(directConv.id(), alice.id(), alice.username(), true);
        verify(messaging, times(2)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("stop signals are throttled independently of start signals")
    void startAndStopDoNotSuppressEachOther() {
        typingService.notifyTyping(directConv.id(), alice.id(), alice.username(), true);
        typingService.notifyTyping(directConv.id(), alice.id(), alice.username(), false);
        verify(messaging, times(2)).convertAndSend(anyString(), any(Object.class));
    }
}
