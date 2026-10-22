package com.example.chat.frontend;

import com.example.chat.application.MessageRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the seam between the server and the browser client.
 *
 * <p>The two halves of this application are written in different languages and
 * are wired up by convention, so a renamed destination or a new HTML sink in the
 * page script would compile, start, and still be wrong: messages would simply
 * stop arriving, or user content would become markup. Neither is caught by the
 * behavioural tests, which speak the protocol directly rather than going
 * through {@code chat.js}. These two checks read the shipped script instead and
 * fail loudly if the contract drifts.</p>
 */
class FrontendContractTest {

    private static final Path CHAT_JS = Path.of("src/main/resources/static/js/app.js");

    private static final Pattern DEST_MAP =
            Pattern.compile("DEST\\s*=\\s*Object\\.freeze\\s*\\((\\{.*?})\\)\\s*;", Pattern.DOTALL);

    private static final Pattern DEST_ENTRY =
            Pattern.compile("([A-Z_]+)\\s*:\\s*'(/[^']*)'");

    /** How far back a handler may open its confirmation and still count as guarding the call. */
    private static final int CONFIRM_WINDOW = 700;

    @Test
    @DisplayName("the browser publishes to exactly the destinations the server serves")
    void browserDestinationsMatchTheServer() throws IOException {
        assertThat(CHAT_JS).as("the shipped browser client").isRegularFile();

        Map<String, String> declared = destinationsIn(Files.readString(CHAT_JS, StandardCharsets.UTF_8));
        assertThat(declared)
                .as("the DEST map in app.js")
                .containsOnlyKeys("JOIN", "USERS", "ERROR_BOX");
        assertThat(declared.get("JOIN"))
                .isEqualTo(MessageRouter.APP_PREFIX + "/chat.join");
        assertThat(declared.get("USERS"))
                .isEqualTo(MessageRouter.TOPIC_USERS);
        assertThat(declared.get("ERROR_BOX"))
                .isEqualTo(MessageRouter.USER_PREFIX + MessageRouter.QUEUE_ERRORS);
    }

    @Test
    @DisplayName("the browser client never writes user content as HTML")
    void browserClientNeverAssignsHtml() throws IOException {
        assertThat(Files.readString(CHAT_JS, StandardCharsets.UTF_8))
                .as("user content must go through textContent; an HTML sink would let a "
                        + "message body such as <img onerror=...> execute in the reader's browser")
                .doesNotContainPattern("(?i)\\.(innerHTML|outerHTML|insertAdjacentHTML)\\s*=");
    }

    @Test
    @DisplayName("the client page stays zoomable and the script distinguishes loading from empty")
    void accessibilityAndLoadingContract() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html)
                .as("blocking zoom breaks WCAG 1.4.4 for low-vision users")
                .doesNotContain("user-scalable=no")
                .doesNotContain("maximum-scale");

        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
        assertThat(js).as("conversation list must show a loading state before the first fetch lands")
                .contains("loading-row")
                .contains("aria-busy");
    }

    @Test
    @DisplayName("settings can be opened from the profile chip and left again on a small screen")
    void settingsNavigationIsWiredBothWays() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html)
                .as("the profile chip has to be a real button, and it has to keep the two ids the "
                        + "script writes the signed-in name and picture into")
                .contains("<button id=\"profile-chip\"")
                .contains("id=\"top-avatar\"")
                .contains("id=\"top-name\"");
        assertThat(html)
                .as("the bottom rail replaces the sidebar on a phone, so the settings page needs its "
                        + "own way back that disappears on desktop where the rail is always visible")
                .containsPattern("(?s)<button id=\"settings-back-btn\"[^>]*class=\"[^\"]*mobile-only[^\"]*\"[^>]*aria-label=");

        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
        assertThat(js)
                .as("tapping the profile chip opens settings, and back returns to the conversation "
                    + "the user was reading rather than dropping them on an empty dashboard")
                .contains("$('profile-chip').addEventListener('click', () => openView('settings'));")
                .contains("$('settings-back-btn').addEventListener('click', () => openView('chats', {")
                .contains("conversationId: state.currentConversation && state.currentConversation.id");
    }

    @Test
    @DisplayName("the shared modal announces itself as a dialog and is dismissible by keyboard")
    void modalIsAnAccessibleDialog() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html)
                .as("without dialog semantics the question read out is whatever the page title happens to be")
                .contains("role=\"dialog\"")
                .contains("aria-modal=\"true\"")
                .contains("aria-labelledby=\"modal-title\"")
                .contains("aria-describedby=\"modal-body\"");

        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
        assertThat(js).as("Escape cancels and Tab cannot wander into the page behind the card")
                .contains("trapModalKeys")
                .contains("event.key === 'Escape'");
    }

    @Test
    @DisplayName("destructive client actions ask for confirmation before they send the request")
    void destructiveActionsConfirmFirst() throws IOException {
        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
        assertThat(js)
                .as("the native dialog cannot be styled or read the way an in-app one can")
                .doesNotContain("window.confirm");
        assertThat(js)
                .as("native confirm() must not come back")
                .doesNotContainPattern("(?<![\\w$.])confirm\\s*\\(");

        destructiveCalls().forEach((call, label) -> {
            int at = js.indexOf(call);
            assertThat(at).as("the client still performs %s", label).isNotNegative();
            int from = Math.max(0, at - CONFIRM_WINDOW);
            assertThat(js.substring(from, at))
                    .as("%s has to be behind a confirmation dialog", label)
                    .contains("await confirmAction(");
        });
    }

    /** Destructive requests the user can fire off from the page, and what each one costs. */
    private static Map<String, String> destructiveCalls() {
        Map<String, String> calls = new LinkedHashMap<>();
        calls.put("await request('/messages/' + msg.id, { method: 'DELETE' });", "deleting a message");
        calls.put("await request('/friends/' + friend.id, { method: 'DELETE' });", "removing a friend");
        calls.put("await request('/conversations/' + conv.id + '/members/' + member.userId, { method: 'DELETE' });", "removing a member");
        calls.put("await request('/conversations/groups/' + conv.id + '/blocks', {", "blocking a member");
        calls.put("await request('/conversations/groups/' + groupId + '/invite-links/' + link.id, { method: 'DELETE' });", "revoking an invite link");
        calls.put("await request('/conversations/' + conv.id + '/leave', { method: 'POST' });", "leaving a group");
        calls.put("await request('/conversations/' + conv.id, { method: 'DELETE' });", "deleting a group");
        calls.put("await request('/auth/logout', { method: 'POST', body: '{}' });", "logging out");
        return calls;
    }

    /** Pulls the {@code DEST} freeze block out of the script. */
    private static Map<String, String> destinationsIn(String source) {
        Matcher map = DEST_MAP.matcher(source);
        assertThat(map.find()).as("the DEST freeze block in chat.js").isTrue();

        Matcher entry = DEST_ENTRY.matcher(map.group(1));
        Map<String, String> declared = new LinkedHashMap<>();
        while (entry.find()) {
            declared.put(entry.group(1), entry.group(2));
        }
        assertThat(declared).as("DEST entries").isNotEmpty();
        return declared;
    }
}
