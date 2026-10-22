package com.example.chat.integration;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.message.PersistentMessageResponse;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP shape of selecting messages and deleting chats: the batch limits are
 * enforced before the service is reached, and the two kinds of deletion answer
 * with the status their blast radius deserves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessageSelectionRestTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private ConversationService conversationService;
    @Autowired private PersistentMessageService messageService;

    private UserProfileResponse alice;
    private UserProfileResponse bob;
    private ConversationSummaryResponse direct;

    @BeforeEach
    void setUp() {
        alice = authService.register(new RegisterRequest("sel_alice", "sel_alice@test.local", "pass123", "Alice Sel"), null);
        bob = authService.register(new RegisterRequest("sel_bob", "sel_bob@test.local", "pass123", "Bob Sel"), null);
        direct = conversationService.getOrCreateDirect(alice.id(), bob.id());
    }

    private String ids(List<String> messageIds) throws Exception {
        return objectMapper.writeValueAsString(Map.of("messageIds", messageIds));
    }

    @Test
    @WithMockUser(username = "sel_alice")
    @DisplayName("a bulk delete of my own messages reports how many went")
    void bulkDeleteOwnMessages() throws Exception {
        PersistentMessageResponse first = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "one");
        PersistentMessageResponse second = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "two");

        mockMvc.perform(post("/api/conversations/" + direct.id() + "/messages/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(List.of(first.id().toString(), second.id().toString()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deleted").value(2));

        mockMvc.perform(get("/api/conversations/" + direct.id() + "/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.content == 'one')]").isEmpty())
            .andExpect(jsonPath("$[?(@.content == 'This message was deleted.')]").isNotEmpty());
    }

    @Test
    @WithMockUser(username = "sel_alice")
    @DisplayName("deleting someone else's message in the batch is refused with 403")
    void bulkDeleteRefusesOtherPeoplesMessages() throws Exception {
        PersistentMessageResponse mine = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "mine");
        PersistentMessageResponse theirs = messageService.sendMessage(direct.id(), bob.id(), bob.username(), "theirs");

        mockMvc.perform(post("/api/conversations/" + direct.id() + "/messages/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(List.of(mine.id().toString(), theirs.id().toString()))))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/conversations/" + direct.id() + "/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.content == 'mine')]").isNotEmpty());
    }

    @Test
    @WithMockUser(username = "sel_alice")
    @DisplayName("an empty or oversized selection is rejected before the service runs")
    void selectionSizeIsValidated() throws Exception {
        mockMvc.perform(post("/api/conversations/" + direct.id() + "/messages/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(List.of())))
            .andExpect(status().isBadRequest());

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add("00000000-0000-0000-0000-000000000000");
        }
        mockMvc.perform(post("/api/conversations/" + direct.id() + "/messages/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(tooMany)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "sel_bob")
    @DisplayName("a stranger's selection is refused outright")
    void bulkDeleteRequiresMembership() throws Exception {
        UserProfileResponse carol = authService.register(new RegisterRequest("sel_carol", "sel_carol@test.local", "pass123", "Carol"), null);
        ConversationSummaryResponse lane = conversationService.getOrCreateDirect(alice.id(), carol.id());
        PersistentMessageResponse note = messageService.sendMessage(lane.id(), carol.id(), carol.username(), "not for bob");

        mockMvc.perform(post("/api/conversations/" + lane.id() + "/messages/bulk-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(List.of(note.id().toString()))))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sel_alice")
    @DisplayName("a bulk forward lands in the destination and is reported")
    void bulkForwardReturnsCopies() throws Exception {
        PersistentMessageResponse first = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "keep 1");
        PersistentMessageResponse second = messageService.sendMessage(direct.id(), alice.id(), alice.username(), "keep 2");
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Selection target", null, null, List.of(bob.id()), false));

        mockMvc.perform(post("/api/conversations/" + group.id() + "/messages/bulk-forward")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "messageIds", List.of(first.id().toString(), second.id().toString()),
                    "clientMessageId", "rest-batch-1"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.forwarded").value(2))
            .andExpect(jsonPath("$.messages[0].forwarded").value(true));

        mockMvc.perform(get("/api/conversations/" + group.id() + "/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.content == 'keep 1')]").isNotEmpty());
    }

    @Test
    @WithMockUser(username = "sel_alice")
    @DisplayName("clearing the chat leaves my history empty and my peer's intact")
    void clearChatForMe() throws Exception {
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "before the clear");
        messageService.sendMessage(direct.id(), bob.id(), bob.username(), "also before");

        mockMvc.perform(post("/api/conversations/" + direct.id() + "/clear"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations/" + direct.id() + "/messages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        messageService.sendMessage(direct.id(), bob.id(), bob.username(), "after the clear");
        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].lastMessageText").value("after the clear"));
    }

    @Test
    @WithMockUser(username = "sel_bob")
    @DisplayName("either participant can delete a direct chat for both")
    void eitherParticipantDeletesDirectChat() throws Exception {
        messageService.sendMessage(direct.id(), alice.id(), alice.username(), "history");

        mockMvc.perform(delete("/api/conversations/" + direct.id()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(username = "sel_alice")
    @DisplayName("the owner can delete a group through the same route")
    void ownerDeletesGroup() throws Exception {
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Selection group", null, null, List.of(bob.id()), false));

        mockMvc.perform(delete("/api/conversations/" + group.id()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title == 'Selection group')]").isEmpty());
    }

    @Test
    @WithMockUser(username = "sel_carol")
    @DisplayName("a member who is not the owner is refused with 403")
    void groupDeletionStaysOwnerOnly() throws Exception {
        UserProfileResponse carol = authService.register(
            new RegisterRequest("sel_carol", "sel_carol@test.local", "pass123", "Carol"), null);
        ConversationSummaryResponse group = conversationService.createGroup(alice.id(),
            new CreateGroupRequest("Carol watches", null, null, List.of(carol.id()), false));

        mockMvc.perform(delete("/api/conversations/" + group.id()))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isNotEmpty());
    }
}
