package com.example.chat.integration;

import com.example.chat.dto.HealthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the HTTP adapter and, at the same time, that the Spring context wires
 * up cleanly with the configured properties.
 *
 * <p>Starting the context is also the check that
 * {@code ChatProperties} binds: an unbound or malformed chat setting would fail
 * here rather than silently falling back to a zero-sized thread pool.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/health reports status and connected-user count")
    void healthReportsStatus() {
        ResponseEntity<HealthResponse> response =
                restTemplate.getForEntity("http://localhost:" + port + "/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
        assertThat(response.getBody().onlineUsers()).isGreaterThanOrEqualTo(0);
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("the static frontend is served from the application root")
    void frontendIsServed() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ChatApp");
    }
}
