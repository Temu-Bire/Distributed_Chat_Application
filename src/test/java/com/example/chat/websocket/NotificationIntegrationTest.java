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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live notifications: who is told, and who is not.
 *
 * <p>These go through the real protocol - REST write, committed transaction,
 * STOMP frame on the recipient's own topic - because the feature is exactly
 * that seam: a preference read from the database, a membership check on the
 * subscription, and a delivery that must not disturb the write that triggered
 * it. A unit test with a mocked template would prove none of that.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SILENCE = Duration.ofMillis(700);

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("a message notifies the other member of the conversation")
    void messageNotifiesTheOtherMember() throws Exception {
        Session alice = register("ntf-a");
        Session bob = register("ntf-b");
        String conversationId = directConversation(alice, bob);

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            send(alice, conversationId, "the build is green again");

            Map<?, ?> note = bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT);
            assertThat(note).as("bob's notification").isNotNull();
            assertThat(note.get("type")).isEqualTo("NEW_MESSAGE");
            assertThat(note.get("category")).isEqualTo("MESSAGE");
            assertThat(note.get("actorUsername")).isEqualTo(alice.username);
            assertThat(note.get("conversationId")).isEqualTo(conversationId);
            assertThat((String) note.get("body")).contains("build is green");
        }
    }

    @Test
    @DisplayName("sending a message never notifies the sender")
    void senderIsNotNotified() throws Exception {
        Session alice = register("ntf-c");
        Session bob = register("ntf-d");
        String conversationId = directConversation(alice, bob);

        try (StompTestClient aliceClient = connect(alice)) {
            aliceClient.subscribe(notificationsOf(alice), Map.class);
            StompTestClient.settle();

            send(alice, conversationId, "talking to myself");

            assertThat(aliceClient.drain(notificationsOf(alice), Map.class, SILENCE)).isEmpty();
        }
    }

    @Test
    @DisplayName("another user cannot subscribe to somebody's notification topic")
    void notificationsArePrivateToTheirOwner() throws Exception {
        Session alice = register("ntf-e");
        Session bob = register("ntf-f");
        Session creep = register("ntf-g");
        String conversationId = directConversation(alice, bob);

        try (StompTestClient creepClient = connect(creep);
             StompTestClient bobClient = connect(bob)) {

            creepClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();
            // A rejected SUBSCRIBE tears the frame off the connection, so the
            // legitimate session is opened after the attempt.
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            send(alice, conversationId, "only bob should see this");

            assertThat(bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT))
                .as("the owner still receives their own notifications").isNotNull();
            assertThat(creepClient.drain(notificationsOf(bob), Map.class, SILENCE))
                .as("a stranger subscribed to another user's topic").isEmpty();
        }
    }

    @Test
    @DisplayName("the master switch silences every category")
    void masterSwitchSilencesEverything() throws Exception {
        Session alice = register("ntf-h");
        Session bob = register("ntf-i");
        String conversationId = directConversation(alice, bob);
        saveSettings(bob, Map.of(
            "theme", "light",
            "notificationsEnabled", false,
            "soundEnabled", true,
            "onlineVisibility", true,
            "notifyMessages", true,
            "notifyMentions", true,
            "notifyFriendRequests", true,
            "notifyGroupActivity", true));

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            send(alice, conversationId, "can you hear me at all");

            assertThat(bobClient.drain(notificationsOf(bob), Map.class, SILENCE)).isEmpty();
        }
    }

    @Test
    @DisplayName("muting messages still lets a mention through")
    void mentionIgnoresTheMutedMessagesCategory() throws Exception {
        Session alice = register("ntf-j");
        Session bob = register("ntf-k");
        Session carol = register("ntf-l");
        String groupId = group(alice, bob, carol);
        saveSettings(bob, Map.of(
            "theme", "light",
            "notificationsEnabled", true,
            "soundEnabled", true,
            "onlineVisibility", true,
            "notifyMessages", false,
            "notifyMentions", true,
            "notifyFriendRequests", true,
            "notifyGroupActivity", true));

        try (StompTestClient bobClient = connect(bob);
             StompTestClient carolClient = connect(carol)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            carolClient.subscribe(notificationsOf(carol), Map.class);
            StompTestClient.settle();

            send(alice, groupId, "release notes are ready");
            assertThat(carolClient.poll(notificationsOf(carol), Map.class, TIMEOUT))
                .as("carol muted nothing").isNotNull();

            send(alice, groupId, "@" + bob.username + " please review the release");

            Map<?, ?> note = bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT);
            assertThat(note).as("a mention reaches a user who muted group chatter").isNotNull();
            assertThat(note.get("type")).isEqualTo("MENTION");
            assertThat(note.get("category")).isEqualTo("MENTION");
        }
    }

    @Test
    @DisplayName("a reply notifies the author of the message being answered")
    void replyNotifiesTheOriginalAuthor() throws Exception {
        Session alice = register("ntf-m");
        Session bob = register("ntf-n");
        String conversationId = directConversation(alice, bob);
        String messageId = send(alice, conversationId, "did we ship the migration");

        try (StompTestClient aliceClient = connect(alice)) {
            aliceClient.subscribe(notificationsOf(alice), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> reply = exchange(bob, HttpMethod.POST,
                "/api/conversations/" + conversationId + "/messages",
                Map.of("content", "yes, V5 is applied", "replyToId", messageId), String.class);
            assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Map<?, ?> note = aliceClient.poll(notificationsOf(alice), Map.class, TIMEOUT);
            assertThat(note).as("alice is told her message was answered").isNotNull();
            assertThat(note.get("type")).isEqualTo("REPLY");
            assertThat(note.get("category")).isEqualTo("MENTION");
            assertThat(note.get("actorUsername")).isEqualTo(bob.username);
        }
    }

    @Test
    @DisplayName("a friend request notifies the receiver")
    void friendRequestNotifiesTheReceiver() throws Exception {
        Session alice = register("ntf-o");
        Session bob = register("ntf-p");

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> response = exchange(alice, HttpMethod.POST, "/api/friends/requests",
                Map.of("userId", bob.userId, "message", "hi, we met at the conference"), String.class);
            assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);

            Map<?, ?> note = bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT);
            assertThat(note).as("bob is told about the request").isNotNull();
            assertThat(note.get("type")).isEqualTo("FRIEND_REQUEST");
            assertThat(note.get("category")).isEqualTo("FRIEND_REQUEST");
            assertThat(note.get("actorUsername")).isEqualTo(alice.username);
            assertThat((String) note.get("body")).contains("conference");
        }
    }

    @Test
    @DisplayName("accepting a friend request notifies the person who sent it")
    void acceptedFriendRequestNotifiesTheSender() throws Exception {
        Session alice = register("ntf-q");
        Session bob = register("ntf-r");

        ResponseEntity<String> created = exchange(alice, HttpMethod.POST, "/api/friends/requests",
            Map.of("userId", bob.userId), String.class);
        assertThat(created.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        String requestId = read(created).get("id").asText();

        try (StompTestClient aliceClient = connect(alice)) {
            aliceClient.subscribe(notificationsOf(alice), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> accepted = exchange(bob, HttpMethod.POST,
                "/api/friends/requests/" + requestId + "/accept", null, String.class);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);

            Map<?, ?> note = aliceClient.poll(notificationsOf(alice), Map.class, TIMEOUT);
            assertThat(note).as("alice learns bob accepted").isNotNull();
            assertThat(note.get("type")).isEqualTo("FRIEND_REQUEST_ACCEPTED");
            assertThat(note.get("actorUsername")).isEqualTo(bob.username);
        }
    }

    @Test
    @DisplayName("a group invitation notifies the invited user")
    void groupInvitationNotifiesTheInvitedUser() throws Exception {
        Session alice = register("ntf-s");
        Session bob = register("ntf-t");
        String groupId = group(alice);

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> response = exchange(alice, HttpMethod.POST,
                "/api/conversations/groups/" + groupId + "/invitations",
                Map.of("userIds", List.of(bob.userId)), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Map<?, ?> note = bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT);
            assertThat(note).as("bob is invited to the group").isNotNull();
            assertThat(note.get("type")).isEqualTo("GROUP_INVITE");
            assertThat(note.get("category")).isEqualTo("GROUP_ACTIVITY");
            assertThat(note.get("conversationId")).isEqualTo(groupId);
            assertThat((String) note.get("title")).isEqualTo("Notifications QA Group");
        }
    }

    @Test
    @DisplayName("a join request notifies the group managers, not the requester")
    void joinRequestNotifiesTheManagers() throws Exception {
        Session alice = register("ntf-u");
        Session bob = register("ntf-v");
        String groupId = group(alice);

        try (StompTestClient aliceClient = connect(alice);
             StompTestClient bobClient = connect(bob)) {
            aliceClient.subscribe(notificationsOf(alice), Map.class);
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> response = exchange(bob, HttpMethod.POST,
                "/api/conversations/groups/" + groupId + "/join-requests", null, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            Map<?, ?> note = aliceClient.poll(notificationsOf(alice), Map.class, TIMEOUT);
            assertThat(note).as("the owner is told about the request").isNotNull();
            assertThat(note.get("type")).isEqualTo("GROUP_JOIN_REQUEST");
            assertThat(note.get("actorUsername")).isEqualTo(bob.username);
            assertThat(bobClient.drain(notificationsOf(bob), Map.class, SILENCE))
                .as("the requester is not notified of their own request").isEmpty();
        }
    }

    @Test
    @DisplayName("an accepted join request tells the applicant they are in")
    void acceptedJoinRequestNotifiesTheApplicant() throws Exception {
        Session alice = register("ntf-w");
        Session bob = register("ntf-x");
        String groupId = group(alice);

        ResponseEntity<String> created = exchange(bob, HttpMethod.POST,
            "/api/conversations/groups/" + groupId + "/join-requests", null, String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String requestId = read(created).get("id").asText();

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> accepted = exchange(alice, HttpMethod.POST,
                "/api/conversations/groups/" + groupId + "/join-requests/" + requestId + "/accept",
                null, String.class);
            assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);

            Map<?, ?> note = bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT);
            assertThat(note).as("bob is told he joined").isNotNull();
            assertThat(note.get("type")).isEqualTo("GROUP_JOIN_ACCEPTED");
            assertThat(note.get("conversationId")).isEqualTo(groupId);
        }
    }

    @Test
    @DisplayName("a group join by an admin add notifies the new member")
    void directMembershipAddNotifiesTheUser() throws Exception {
        Session alice = register("ntf-y");
        Session bob = register("ntf-z");
        String groupId = group(alice);

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            ResponseEntity<String> response = exchange(alice, HttpMethod.POST,
                "/api/conversations/" + groupId + "/members",
                Map.of("userId", bob.userId), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            Map<?, ?> note = bobClient.poll(notificationsOf(bob), Map.class, TIMEOUT);
            assertThat(note).as("being added silently is still a notification").isNotNull();
            assertThat(note.get("type")).isEqualTo("GROUP_INVITE");
            assertThat(note.get("conversationId")).isEqualTo(groupId);
        }
    }

    @Test
    @DisplayName("per-category switches are read from the recipient's own settings")
    void friendRequestCategorySwitchIsHonoured() throws Exception {
        Session alice = register("ntf-aa");
        Session bob = register("ntf-ab");
        saveSettings(bob, Map.of(
            "theme", "light",
            "notificationsEnabled", true,
            "soundEnabled", true,
            "onlineVisibility", true,
            "notifyMessages", true,
            "notifyMentions", true,
            "notifyFriendRequests", false,
            "notifyGroupActivity", true));

        try (StompTestClient bobClient = connect(bob)) {
            bobClient.subscribe(notificationsOf(bob), Map.class);
            StompTestClient.settle();

            exchange(alice, HttpMethod.POST, "/api/friends/requests", Map.of("userId", bob.userId), String.class);

            assertThat(bobClient.drain(notificationsOf(bob), Map.class, SILENCE))
                .as("bob asked not to be told about friend requests").isEmpty();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Session(String username, String cookie, String userId) {}

    private StompTestClient connect(Session session) throws Exception {
        return StompTestClient.connect(port, objectMapper, session.cookie);
    }

    private static String notificationsOf(Session session) {
        return "/topic/notifications/" + session.userId;
    }

    private Session register(String prefix) throws Exception {
        String raw = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String username = raw.length() > 20 ? raw.substring(0, 20) : raw;
        Map<String, Object> body = Map.of(
            "username", username,
            "displayName", username,
            "email", username + "@notification-test.local",
            "password", "Password123!");
        ResponseEntity<String> response = exchangeRaw(null, HttpMethod.POST, "/api/auth/register", body, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(response.getBody());
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        return new Session(username, setCookie.substring(0, setCookie.indexOf(';')), json.get("id").asText());
    }

    private String directConversation(Session owner, Session other) throws Exception {
        ResponseEntity<String> response = exchange(owner, HttpMethod.POST, "/api/conversations/direct",
            Map.of("recipientId", other.userId), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response).get("id").asText();
    }

    /** Creates a group with its members already in place; the caller is the owner. */
    private String group(Session owner, Session... members) throws Exception {
        List<String> memberIds = new ArrayList<>();
        for (Session member : members) {
            memberIds.add(member.userId);
        }
        ResponseEntity<String> response = exchange(owner, HttpMethod.POST, "/api/conversations/group",
            Map.of("title", "Notifications QA Group", "memberIds", memberIds), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response).get("id").asText();
    }

    private String send(Session sender, String conversationId, String content) throws Exception {
        ResponseEntity<String> response = exchange(sender, HttpMethod.POST,
            "/api/conversations/" + conversationId + "/messages", Map.of("content", content), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response).get("id").asText();
    }

    private void saveSettings(Session session, Map<String, Object> settings) {
        ResponseEntity<String> response = exchange(session, HttpMethod.PUT, "/api/settings", settings, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode read(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
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
