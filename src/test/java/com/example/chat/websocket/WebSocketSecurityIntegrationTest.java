package com.example.chat.websocket;

import com.example.chat.integration.StompTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the STOMP subscription guard actually stops eavesdropping:
 * a member's subscription receives conversation events, while a non-member
 * (authenticated or anonymous) subscribed to the same topic receives nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketSecurityIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("a conversation member receives real-time message events")
    void memberReceivesConversationEvents() throws Exception {
        Session alice = register("eav-alice-" + UUID.randomUUID().toString().substring(0, 6));
        Session bob = register("eav-bob-" + UUID.randomUUID().toString().substring(0, 6));
        String conversationId = directConversation(alice, bob);

        try (StompTestClient aliceClient = StompTestClient.connect(port, objectMapper, alice.cookie)) {
            aliceClient.subscribe("/topic/conversations/" + conversationId, Map.class);
            StompTestClient.settle();

            send(alice, conversationId, "hello member world");

            Map<?, ?> frame = aliceClient.poll("/topic/conversations/" + conversationId, Map.class, TIMEOUT);
            assertThat(frame).as("event frame for a member").isNotNull();
            assertThat(frame.get("event")).isEqualTo("NEW_MESSAGE");
        }
    }

    @Test
    @DisplayName("an authenticated non-member subscribed to the topic receives nothing")
    void nonMemberIsSilenced() throws Exception {
        Session alice = register("eav2-alice-" + UUID.randomUUID().toString().substring(0, 6));
        Session bob = register("eav2-bob-" + UUID.randomUUID().toString().substring(0, 6));
        Session creep = register("eav2-creep-" + UUID.randomUUID().toString().substring(0, 6));
        String conversationId = directConversation(alice, bob);

        try (StompTestClient memberClient = StompTestClient.connect(port, objectMapper, alice.cookie);
             StompTestClient creepClient = StompTestClient.connect(port, objectMapper, creep.cookie)) {

            memberClient.subscribe("/topic/conversations/" + conversationId, Map.class);
            creepClient.subscribe("/topic/conversations/" + conversationId, Map.class);
            StompTestClient.settle();

            send(alice, conversationId, "private chatter");

            Map<?, ?> received = memberClient.poll(
                    "/topic/conversations/" + conversationId, Map.class, TIMEOUT);
            assertThat(received).as("member still gets events").isNotNull();

            List<?> leaked = creepClient.drain(
                    "/topic/conversations/" + conversationId, Map.class, Duration.ofMillis(600));
            assertThat(leaked).as("non-member must not receive the conversation topic").isEmpty();
        }

        // The REST side refuses the same attempt: no direct object reference.
        ResponseEntity<String> history = exchange(creep, HttpMethod.GET,
                "/api/conversations/" + conversationId + "/messages?size=10", null, String.class);
        assertThat(history.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an anonymous connection cannot subscribe to a conversation topic")
    void anonymousSubscriptionIsRejected() throws Exception {
        Session alice = register("eav3-alice-" + UUID.randomUUID().toString().substring(0, 6));
        Session bob = register("eav3-bob-" + UUID.randomUUID().toString().substring(0, 6));
        String conversationId = directConversation(alice, bob);

        try (StompTestClient memberClient = StompTestClient.connect(port, objectMapper, alice.cookie);
             StompTestClient anonClient = StompTestClient.connect(port, objectMapper, null)) {

            memberClient.subscribe("/topic/conversations/" + conversationId, Map.class);
            anonClient.subscribe("/topic/conversations/" + conversationId, Map.class);
            StompTestClient.settle();

            send(bob, conversationId, "still private");

            assertThat(memberClient.poll("/topic/conversations/" + conversationId, Map.class, TIMEOUT))
                    .as("member receives while anonymous is cut off").isNotNull();
            assertThat(anonClient.drain("/topic/conversations/" + conversationId, Map.class, Duration.ofMillis(600)))
                    .isEmpty();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Session(String username, String cookie, String userId) {}

    private Session register(String username) throws Exception {
        String shortName = username.length() > 20 ? username.substring(0, 20) : username;
        Map<String, Object> body = Map.of(
                "username", shortName,
                "displayName", shortName,
                "email", shortName + "@security-test.local",
                "password", "Password123!");
        ResponseEntity<String> response = exchangeRaw(null, HttpMethod.POST, "/api/auth/register", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(response.getBody());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return new Session(shortName, setCookie.substring(0, setCookie.indexOf(';')), json.get("id").asText());
    }

    private String directConversation(Session owner, Session other) throws Exception {
        Map<String, Object> body = Map.of("recipientId", other.userId);
        ResponseEntity<String> response = exchange(owner, HttpMethod.POST, "/api/conversations/direct", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asText();
    }

    private void send(Session session, String conversationId, String content) {
        ResponseEntity<String> response = exchange(session, HttpMethod.POST,
                "/api/conversations/" + conversationId + "/messages",
                Map.of("content", content), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<String> exchange(Session session, HttpMethod method, String path,
                                            Object body, Class<String> responseType) {
        return exchangeRaw(session == null ? null : session.cookie, method, path, body, responseType);
    }

    private ResponseEntity<String> exchangeRaw(String cookie, HttpMethod method, String path,
                                               Object body, Class<String> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add("Cookie", cookie);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }
}
