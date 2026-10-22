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

    private static final Path STYLES_CSS = Path.of("src/main/resources/static/css/styles.css");

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
    @DisplayName("a person row opens the profile card, and the card only shows what the endpoint dares to")
    void profileCardsAreWiredToThePeopleRows() throws IOException {
        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);

        assertThat(js)
                .as("the card reads GET /api/users/{id}, the payload built without an email address")
                .contains("request('/users/' + userId)");
        assertThat(js)
                .as("the ways in: the friends and request rows, the people search, the group roster, "
                        + "and the info button of a private chat")
                .contains("attachRowOpener(row, avatar, mid,")
                .contains("() => openUserProfile(profileIdOf(person)));")
                .contains("() => openUserProfile(person.id));")
                .contains("() => openUserProfile(member.userId));")
                .contains("else openUserProfile(conv.otherUserId);");
        assertThat(js)
                .as("a row that opens a card has to say which card, and the avatar and name move "
                        + "inside the control so they are clickable and read out together")
                .contains("opener.setAttribute('aria-label', 'View ' + label);")
                .contains("row.appendChild(opener);");
        assertThat(js)
                .as("a request row's own id belongs to the request, so the card has to come from the "
                        + "person on the other side of it")
                .contains("}, (req) => req.senderId);")
                .contains("}, (req) => req.receiverId);");
        assertThat(js)
                .as("someone who hid their status is shown as hidden, not reported as offline")
                .contains("profile.shareOnlineStatus ? 'Offline' : 'Online status hidden'");
        assertThat(js)
                .as("the header dot used to be shown for any group of one, which proved nothing")
                .doesNotContain("$('chat-online-dot').hidden = isGroup(conv);");
        assertThat(js)
                .as("the presence topic carries the raw registry, connected accounts that hid their "
                        + "status included, so a frame may only trigger a re-read of the REST view")
                .contains("client.subscribe(DEST.USERS, () => schedulePresenceRefresh());")
                .doesNotContain("client.subscribe(DEST.USERS, () => {});");
    }

    @Test
    @DisplayName("the group card is read from the profile endpoint and only shows the roster it was given")
    void groupCardsFollowTheServersRosterRules() throws IOException {
        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);

        assertThat(js)
                .as("both the card and the group page read GET /api/conversations/groups/{id}/profile: "
                        + "the one endpoint that decides whether this caller gets names")
                .contains("request('/conversations/groups/' + groupId + '/profile')")
                .contains("request('/conversations/groups/' + conv.id + '/profile')");
        assertThat(js)
                .as("a stranger on a public group is sent an empty roster, so the list has to be "
                        + "skipped rather than rendered as a heading over nothing")
                .contains("if (group.members.length) {");
        assertThat(js)
                .as("the discover row is a doorway to the card, which is where the description, the "
                        + "founding date and the membership state are")
                .contains("attachRowOpener(row, avatar, mid, 'the profile of ' + group.title,")
                .contains("() => openGroupProfile(group.id));");
        assertThat(js)
                .as("joining or asking to join from the card uses the same endpoints as the "
                        + "discovery list and re-reads it, so the group leaves the list it was found on")
                .contains("request('/conversations/groups/' + group.id + '/join', { method: 'POST' })")
                .contains("request('/conversations/groups/' + group.id + '/join-requests', { method: 'POST' })")
                .contains("await loadDiscoverGroups();");
        assertThat(js)
                .as("a private group answers 403 before the card can show a roster, and the modal must "
                        + "not be left open on an error toast")
                .containsPattern("(?s)catch \\(err\\) \\{\\s*if \\(token === cardSeq\\) \\{\\s*closeModal\\(\\);")
                .contains("if (token !== cardSeq || $('modal').hidden) return;");
        assertThat(js)
                .as("the chat header counts members from the group's own numbers, and says so in the "
                        + "singular for a group of one")
                .contains("conv.memberCount === 1 ? ' member' : ' members'");

        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html)
                .as("the group page shows the card as text: the edit form below it is only for the "
                        + "roles that may save it, so the facts need their own sinks")
                .contains("id=\"group-name-view\"")
                .contains("id=\"group-desc-view\"")
                .contains("id=\"group-facts\"")
                .contains("id=\"group-info-edit\"");
        assertThat(js)
                .as("plain members get the facts, not a form whose Save button would be refused")
                .contains("$('group-info-edit').hidden = !isAdmin;");
    }

    @Test
    @DisplayName("the chat header opens the card of whoever is being talked to")
    void chatHeaderOpensTheRightCard() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html)
                .as("the picture and the name are one control, and a button may only hold phrasing "
                        + "content, so the title is a span rather than a heading")
                .containsPattern("(?s)<button id=\"chat-person-btn\"[^>]*>.*?id=\"chat-avatar\".*?id=\"chat-title\"")
                .doesNotContain("<h2 id=\"chat-title\"");

        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
        assertThat(js)
                .as("a group gets the group card, a private chat gets the person's card, and the "
                        + "control names the card it opens")
                .contains("$('chat-person-btn').addEventListener('click', () => {")
                .contains("if (isGroup(conv)) openGroupProfile(conv.id);")
                .contains("'View ' + convTitle(conv) + (isGroup(conv) ? ' group info' : ' profile')");
    }

    @Test
    @DisplayName("the phone rail stays on screen whatever page is open")
    void mobileRailStaysOnScreen() throws IOException {
        String css = Files.readString(STYLES_CSS, StandardCharsets.UTF_8);
        assertThat(css)
                .as("a bare 1fr row is minmax(auto, 1fr): a tall page grows it and pushes the rail "
                        + "below the fold, so the scrolling area has to be capped at a zero minimum")
                .containsPattern("(?s)@media \\(max-width: 980px\\).*?grid-template-rows: minmax\\(0, 1fr\\) 64px");
    }

    @Test
    @DisplayName("the composer offers a picker and takes a picture from paste, drop or paperclip")
    void composerHasAnEmojiPickerAndThreeUploadDoors() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        assertThat(html)
                .as("the popover the script fills, declared in the page so it is in the tab order")
                .contains("id=\"composer-picker\"");

        String js = Files.readString(CHAT_JS, StandardCharsets.UTF_8);
        assertThat(js)
                .as("a handful of emojis, inserted at the caret instead of one hardcoded smiley "
                        + "glued onto the end of the draft")
                .contains("COMPOSER_EMOJIS")
                .contains("insertAtCaret($('message-content-input'), emoji)")
                .doesNotContain("input.value += '\uD83D\uDE0A'");

        assertThat(js)
                .as("the paperclip, a paste and a drop all go through the one upload path, and the "
                        + "window listeners are what stop a dropped file from replacing the app")
                .contains("$('attach-input').addEventListener('change', chooseAttachment)")
                .contains("$('message-content-input').addEventListener('paste', onComposerPaste)")
                .contains("zone.addEventListener('drop'")
                .contains("window.addEventListener('drop', (event) => event.preventDefault());")
                .contains("fetch(API + '/uploads/media'");
        assertThat(js)
                .as("a pasted picture must not also paste its text form into the draft")
                .containsPattern("(?s)function onComposerPaste\\(event\\) \\{[^}]*event\\.preventDefault\\(\\);");
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
        calls.put("await request('/friends/' + profile.id, { method: 'DELETE' });", "unfriending from a profile card");
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
