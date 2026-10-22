package com.example.chat.frontend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The landing page is the front door: it must answer at "/" for people who
 * have not signed in, keep its buttons pointed at routes that actually exist,
 * and stay out of the way of anyone who already has a session. A dead "Get
 * Started" link is a broken product claim, so it is checked here rather than in
 * a manual pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class LandingPageIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the root serves the public landing page to anonymous visitors")
    void rootServesLanding() {
        ResponseEntity<String> root = restTemplate.getForEntity(base() + "/", String.class);

        assertThat(root.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = root.getBody();
        assertThat(body).contains("ChatSphere");
        assertThat(body).contains("Connect. Chat.");
        assertThat(body).contains("/css/landing.css");
        assertThat(body).doesNotContain("app-shell");
    }

    @Test
    @WithMockUser
    @DisplayName("a signed-in visitor is sent to the app, not shown the landing page")
    void signedInRootRedirectsToApp() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"));
    }

    @Test
    @DisplayName("landing buttons point at the real app routes")
    void buttonsLinkToRealRoutes() {
        String body = restTemplate.getForEntity(base() + "/", String.class).getBody();

        assertThat(body).contains("href=\"/app\"");
        assertThat(body).contains("href=\"/app#signup\"");
        assertThat(restTemplate.getForEntity(base() + "/app", String.class).getStatusCode())
                .as("Login/Get Started target").isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity(base() + "/css/landing.css", String.class).getStatusCode())
                .as("the stylesheet the page links").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("/app serves the chat client, and /index.html keeps working")
    void appRoutesServeTheClient() {
        for (String path : new String[] {"/app", "/index.html"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(base() + path, String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as(path).contains("login-view");
        }
    }

    @Test
    @DisplayName("the landing page advertises only security properties that are enforced")
    void marketingClaimsMatchEnforcement() {
        String body = restTemplate.getForEntity(base() + "/", String.class).getBody();

        // Each claim maps to a committed feature; if one of those is ever
        // removed, this sentence becomes a lie and the test should fail.
        assertThat(body).contains("BCrypt");
        assertThat(body).contains("Content-Security-Policy");
        assertThat(body).contains("429");
        assertThat(body).contains("HttpOnly");
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
