package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.FriendService;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.friend.FriendRequestResponse;
import com.example.chat.dto.friend.SendFriendRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FriendControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private FriendService friendService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserProfileResponse userA;
    private UserProfileResponse userB;

    @BeforeEach
    void setUp() {
        userA = authService.register(new RegisterRequest("fctrl_a", "fctrl_a@test.com", "pass123", "User A"), null);
        userB = authService.register(new RegisterRequest("fctrl_b", "fctrl_b@test.com", "pass123", "User B"), null);
    }

    @Test
    @DisplayName("unauthenticated access to /api/friends is rejected with 401 or 403")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/friends"))
            .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = "fctrl_a")
    @DisplayName("user A sends friend request to user B")
    void sendFriendRequest() throws Exception {
        SendFriendRequest req = new SendFriendRequest(userB.username(), null);

        mockMvc.perform(post("/api/friends/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.senderUsername").value("fctrl_a"))
            .andExpect(jsonPath("$.receiverUsername").value("fctrl_b"))
            .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @WithMockUser(username = "fctrl_b")
    @DisplayName("user B views incoming requests and accepts")
    void viewAndAcceptRequest() throws Exception {
        FriendRequestResponse sent = friendService.sendFriendRequest(userA.id(), new SendFriendRequest(userB.username(), null));

        mockMvc.perform(get("/api/friends/requests/incoming"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(sent.id().toString()))
            .andExpect(jsonPath("$[0].senderUsername").value("fctrl_a"));

        mockMvc.perform(post("/api/friends/requests/" + sent.id() + "/accept"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/api/friends"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].username").value("fctrl_a"));
    }
}
