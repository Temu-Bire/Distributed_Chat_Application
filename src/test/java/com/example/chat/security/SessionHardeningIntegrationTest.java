package com.example.chat.security;

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
 * Session and transport hardening, asserted against the live HTTP surface:
 *
 * <ul>
 *   <li>the session cookie is unreadable from scripts (HttpOnly) and is not
 *       replayed on cross-site posts (SameSite=Lax) - the mitigation that
 *       makes CSRF-less auth tolerable;</li>
 *   <li>authentication mints a fresh session id, so an id an attacker
 *       planted or observed before login stops being the victim's session
 *       (fixation);</li>
 *   <li>pages carry the CSP and friends, so injected markup cannot fetch or
 *       frame its way out.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionHardeningIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("the session cookie is HttpOnly and SameSite=Lax")
    void sessionCookieFlags() {
        String username = "sh" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> response = post("/api/auth/register", Map.of(
                "username", username,
                "displayName", username,
                "email", username + "@session-test.local",
                "password", "Password123!"), null);

        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.toLowerCase()).contains("httponly");
        assertThat(cookie.toLowerCase()).contains("samesite=lax");
    }

    @Test
    @DisplayName("login rotates the session id and the pre-login id stops working")
    void loginRotatesTheSessionId() {
        String username = "sf" + UUID.randomUUID().toString().substring(0, 8);
        String planted = sessionId(post("/api/auth/register", Map.of(
                "username", username,
                "displayName", username,
                "email", username + "@session-test.local",
                "password", "Password123!"), null));
        assertThat(planted).isNotBlank();

        // A session that existed before authentication must not survive it.
        String afterLogin = sessionId(post("/api/auth/login", Map.of(
                "email", username + "@session-test.local",
                "password", "Password123!"), planted));
        assertThat(afterLogin).isNotBlank();
        assertThat(afterLogin).isNotEqualTo(planted);

        assertThat(statusCodeFor("/api/auth/status", afterLogin)).isEqualTo(HttpStatus.OK);
        assertThat(bodyFor("/api/auth/status", afterLogin)).contains("\"authenticated\":true");
        assertThat(bodyFor("/api/auth/status", planted))
                .as("the id an attacker may have observed no longer carries the session")
                .doesNotContain("\"authenticated\":true");
    }

    @Test
    @DisplayName("pages are served with the locking-down security headers")
    void securityHeadersGuardPages() {
        ResponseEntity<String> page = restTemplate.getForEntity("/index.html", String.class);

        String csp = page.getHeaders().getFirst("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("default-src 'self'");
        assertThat(csp).contains("script-src 'self'");
        assertThat(csp).doesNotContain("unsafe-inline");
        assertThat(csp).doesNotContain("unsafe-eval");
        assertThat(csp).contains("frame-ancestors 'none'");

        assertThat(page.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> post(String path, Object body, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add("Cookie", "JSESSIONID=" + cookie);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private org.springframework.http.HttpStatusCode statusCodeFor(String path, String sessionId) {
        return exchange(path, sessionId).getStatusCode();
    }

    private String bodyFor(String path, String sessionId) {
        return exchange(path, sessionId).getBody();
    }

    private ResponseEntity<String> exchange(String path, String sessionId) {
        HttpHeaders headers = new HttpHeaders();
        if (sessionId != null) {
            headers.add("Cookie", "JSESSIONID=" + sessionId);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** Pulls the bare session id out of a Set-Cookie header, or empty when absent. */
    private static String sessionId(ResponseEntity<String> response) {
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (cookie == null || !cookie.startsWith("JSESSIONID=")) {
            return "";
        }
        String value = cookie.substring("JSESSIONID=".length());
        int semicolon = value.indexOf(';');
        return semicolon < 0 ? value : value.substring(0, semicolon);
    }
}
