package com.example.chat.security;

import com.example.chat.exception.ChatException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the rate limits are real over HTTP: with tight budgets applied, the
 * (limit+1)-th attempt in a minute is refused with 429, {@code RATE_LIMITED}
 * and a {@code Retry-After} header, while unrelated traffic still passes.
 *
 * <p>The budgets are deliberately tiny here; production values live in
 * {@code application.yml} and the defaults in {@code RateLimitProperties}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "chat.rate-limit.enabled=true",
        "chat.rate-limit.login-per-minute=3",
        "chat.rate-limit.register-per-minute=20",
        "chat.rate-limit.friend-request-per-minute=2",
        "chat.rate-limit.messages-per-minute=3"
})
class RateLimitIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiter rateLimiter;

    @BeforeEach
    void freshBudgets() {
        // Real clock, one-minute windows: without this the tests would share
        // the budget consumed by whoever ran before them.
        rateLimiter.reset();
    }

    @Test
    @DisplayName("the fourth login in a minute is refused with 429 and Retry-After")
    void loginIsThrottledPerIp() throws Exception {
        String username = unique("rl-login");
        register(username);

        for (int i = 0; i < 3; i++) {
            assertThat(login(username).getStatusCode())
                    .as("attempt %d within the budget", i + 1)
                    .isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> refused = login(username);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(code(refused)).isEqualTo(ChatException.RATE_LIMITED);

        // The wall is per client, per endpoint family: another account from a
        // different context would have its own budget, and registration (a
        // different key prefix) is untouched by login attempts.
        // A different key prefix keeps its own budget: registration still
        // succeeds (asserts 201 internally) while login is walled off.
        register(unique("rl-reg"));
    }

    @Test
    @DisplayName("the message budget is per sender and only blocks new persists")
    void messageBudgetIsPerSender() throws Exception {
        Session alice = register(unique("rl-alice"));
        Session bob = register(unique("rl-bob"));
        String conversationId = directConversation(alice, bob);

        for (int i = 0; i < 3; i++) {
            assertThat(send(alice, conversationId, "msg " + i).getStatusCode())
                    .as("send %d within the budget", i + 1)
                    .isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> refused = send(alice, conversationId, "one too many");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(code(refused)).isEqualTo(ChatException.RATE_LIMITED);

        assertThat(send(bob, conversationId, "bob is not punished for alice's flood")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Reads are not writes: history stays available while the sender is throttled.
        ResponseEntity<String> history = exchange(alice.cookie, HttpMethod.GET,
                "/api/conversations/" + conversationId + "/messages?size=10", null, String.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("friend requests hit a per-user budget that another user's traffic does not consume")
    void friendRequestsAreThrottledPerUser() throws Exception {
        Session alice = register(unique("rl-fa"));
        Session target1 = register(unique("rl-fb"));
        Session target2 = register(unique("rl-fc"));
        Session target3 = register(unique("rl-fd"));
        Session other = register(unique("rl-oe"));

        assertThat(friendRequest(alice, target1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(friendRequest(alice, target2).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> refused = friendRequest(alice, target3);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(code(refused)).isEqualTo(ChatException.RATE_LIMITED);

        assertThat(friendRequest(other, target1).getStatusCode())
                .as("alice's flood must not spend bob's budget")
                .isEqualTo(HttpStatus.CREATED);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Session(String username, String cookie, String userId) {}

    private static String unique(String prefix) {
        String name = prefix + UUID.randomUUID().toString().substring(0, 6);
        return name.length() > 20 ? name.substring(0, 20) : name;
    }

    private Session register(String username) throws Exception {
        Map<String, Object> body = Map.of(
                "username", username,
                "displayName", username,
                "email", username + "@rate-test.local",
                "password", "Password123!");
        ResponseEntity<String> response = exchange(null, HttpMethod.POST, "/api/auth/register", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(response.getBody());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return new Session(username, setCookie.substring(0, setCookie.indexOf(';')), json.get("id").asText());
    }

    private ResponseEntity<String> login(String username) {
        return exchange(null, HttpMethod.POST, "/api/auth/login",
                Map.of("email", username + "@rate-test.local", "password", "Password123!"), String.class);
    }

    private String directConversation(Session owner, Session other) throws Exception {
        ResponseEntity<String> response = exchange(owner.cookie, HttpMethod.POST,
                "/api/conversations/direct", Map.of("recipientId", other.userId), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).get("id").asText();
    }

    private ResponseEntity<String> send(Session session, String conversationId, String content) {
        return exchange(session.cookie, HttpMethod.POST,
                "/api/conversations/" + conversationId + "/messages",
                Map.of("content", content), String.class);
    }

    private ResponseEntity<String> friendRequest(Session from, Session target) {
        return exchange(from.cookie, HttpMethod.POST, "/api/friends/requests",
                Map.of("username", target.username, "userId", target.userId), String.class);
    }

    private ResponseEntity<String> exchange(String cookie, HttpMethod method, String path,
                                            Object body, Class<String> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add("Cookie", cookie);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), responseType);
    }

    private String code(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).get("code").asText();
    }
}
