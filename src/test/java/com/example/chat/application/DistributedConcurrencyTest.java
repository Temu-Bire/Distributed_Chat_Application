package com.example.chat.application;

import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.message.PersistentMessageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DistributedConcurrencyTest {

    @Autowired
    private PersistentMessageService messageService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private AuthService authService;

    @Test
    @DisplayName("concurrent message sending from multiple users into the same conversation maintains consistency")
    void concurrentMessageSending() throws Exception {
        String suffix = System.currentTimeMillis() + "";
        UserProfileResponse user1 = authService.register(new RegisterRequest("conc1_" + suffix, "conc1_" + suffix + "@test.com", "pass123", "Conc One"), null);
        UserProfileResponse user2 = authService.register(new RegisterRequest("conc2_" + suffix, "conc2_" + suffix + "@test.com", "pass123", "Conc Two"), null);

        ConversationSummaryResponse conv = conversationService.getOrCreateDirect(user1.id(), user2.id());

        int threadCount = 10;
        int messagesPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Future<List<PersistentMessageResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            final UserProfileResponse sender = (threadIdx % 2 == 0) ? user1 : user2;
            futures.add(executor.submit(() -> {
                startLatch.await();
                List<PersistentMessageResponse> sent = new ArrayList<>();
                for (int m = 0; m < messagesPerThread; m++) {
                    sent.add(messageService.sendMessage(
                        conv.id(), sender.id(), sender.username(), "Msg from thread " + threadIdx + " count " + m
                    ));
                }
                doneLatch.countDown();
                return sent;
            }));
        }

        // Fire all threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        // Verify that total messages received equals expected (50 messages)
        List<PersistentMessageResponse> allMessages = messageService.getMessages(conv.id(), user1.id(), 0, 100);
        assertThat(allMessages).hasSize(threadCount * messagesPerThread);
    }
}
