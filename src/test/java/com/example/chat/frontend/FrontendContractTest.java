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
