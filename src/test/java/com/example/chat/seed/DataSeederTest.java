package com.example.chat.seed;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.FriendService;
import com.example.chat.application.GroupInvitationService;
import com.example.chat.application.GroupInviteLinkService;
import com.example.chat.application.GroupJoinRequestService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.domain.entity.UserSettingsEntity;
import com.example.chat.dto.auth.LoginRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationMemberResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.group.BlockedUserResponse;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What the seed must actually produce.
 *
 * <p>Seeding exists so a developer can open the app and see a working system,
 * so these tests assert on the things a person would look for: can I log in, is
 * there a friend request waiting, does the pin bar have a pin in it, does a
 * search return something. A seeder that ran without error but produced an
 * empty-looking app would pass a weaker test.</p>
 *
 * <p>Runs on its own in-memory database: the suite shares one, and ten extra
 * accounts visible to unrelated tests would make every user-count assertion in
 * the repository depend on which contexts had already started.</p>
 */
// A dedicated in-memory database keeps the ten seeded accounts out of the
// database the rest of the suite shares.
@SpringBootTest(properties = {
    "chat.seed.enabled=true",
    "spring.datasource.url=jdbc:h2:mem:seed_check;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
class DataSeederTest {

    @Autowired private DataSeeder seeder;
    @Autowired private AuthService authService;
    @Autowired private FriendService friendService;
    @Autowired private ConversationService conversationService;
    @Autowired private PersistentMessageService messageService;
    @Autowired private GroupJoinRequestService joinRequestService;
    @Autowired private GroupInvitationService invitationService;
    @Autowired private GroupInviteLinkService inviteLinkService;
    @Autowired private UserSettingsRepository settingsRepository;

    private UUID miraId;
    private UUID dannyId;

    @BeforeEach
    void resolveSeedUsers() {
        miraId = authService.login(new LoginRequest("mira@seed.local", DataSeeder.PASSWORD), null).id();
        dannyId = authService.login(new LoginRequest("danny@seed.local", DataSeeder.PASSWORD), null).id();
    }

    @Test
    @DisplayName("every seeded account can log in with the shared password")
    void accountsAreUsable() {
        assertThatCode(() -> authService.login(new LoginRequest("phil@seed.local", DataSeeder.PASSWORD), null))
            .doesNotThrowAnyException();

        List<UserProfileResponse> all = List.of(
            "mira", "danny", "lena", "tomas", "sara", "owen", "ravi", "yuki", "nadia", "phil")
            .stream()
            .map(username -> authService.login(new LoginRequest(username + "@seed.local", DataSeeder.PASSWORD), null))
            .toList();
        assertThat(all).hasSize(10);
        assertThat(all).allSatisfy(user -> assertThat(user.displayName()).isNotBlank());
        assertThat(all).allSatisfy(user -> assertThat(user.bio()).isNotBlank());

        UserProfileResponse mira = all.get(0);
        assertThat(mira.bio()).isEqualTo("Keeps the roadmap honest.");
    }

    @Test
    @DisplayName("accepted friendships are paired and requests stay pending")
    void friendGraphIsPopulated() {
        assertThat(friendService.getFriends(miraId))
            .extracting(friend -> friend.username())
            .contains("danny", "lena", "sara");

        assertThat(friendService.getIncomingRequests(
            authService.login(new LoginRequest("nadia@seed.local", DataSeeder.PASSWORD), null).id()))
            .anySatisfy(request -> assertThat(request.senderUsername()).isEqualTo("tomas"));

        // phil's request to mira is deliberately unanswered, so the inbox is not empty.
        assertThat(friendService.getIncomingRequests(miraId))
            .anySatisfy(request -> assertThat(request.senderUsername()).isEqualTo("phil"));
    }

    @Test
    @DisplayName("the three groups carry a moderator, a join request, an invite and a block")
    void groupsCoverTheModerationSurface() {
        ConversationSummaryResponse design = groupByTitle(miraId, "Design Standup");
        assertThat(design.isPrivate()).isFalse();
        assertThat(conversationService.getConversationMembers(design.id(), miraId))
            .filteredOn(member -> member.username().equals("lena"))
            .singleElement()
            .extracting(ConversationMemberResponse::role)
            .isEqualTo(MemberRole.MODERATOR);

        UUID saraId = authService.login(new LoginRequest("sara@seed.local", DataSeeder.PASSWORD), null).id();
        assertThat(joinRequestService.getPendingRequestsForGroup(design.id(), miraId))
            .anySatisfy(request -> assertThat(request.username()).isEqualTo("sara"));
        assertThat(inviteLinkService.listActive(miraId, design.id())).hasSize(1);

        ConversationSummaryResponse climbing = groupByTitle(saraId, "Weekend Climbing");
        assertThat(climbing.isPrivate()).isTrue();
        UUID raviId = authService.login(new LoginRequest("ravi@seed.local", DataSeeder.PASSWORD), null).id();
        assertThat(invitationService.getUserInvitations(raviId))
            .anySatisfy(invite -> assertThat(invite.groupId()).isEqualTo(climbing.id()));

        ConversationSummaryResponse build = groupByTitle(
            authService.login(new LoginRequest("tomas@seed.local", DataSeeder.PASSWORD), null).id(), "Build Failures");
        assertThat(conversationService.getBlockedMembers(build.id(),
            authService.login(new LoginRequest("tomas@seed.local", DataSeeder.PASSWORD), null).id()))
            .extracting(BlockedUserResponse::username)
            .contains("phil");
    }

    @Test
    @DisplayName("the thread contains one of every message feature the app renders")
    void messagesExerciseEveryFeature() {
        ConversationSummaryResponse design = groupByTitle(miraId, "Design Standup");
        assertThat(messageService.getPinnedMessages(design.id(), miraId))
            .singleElement()
            .satisfies(message -> assertThat(message.content()).contains("Notes live in the shared doc"));

        ConversationSummaryResponse build = groupByTitle(dannyId, "Build Failures");
        List<PersistentMessageResponse> buildMessages = messageService.getMessages(build.id(), dannyId, 0, 50);
        assertThat(buildMessages).anySatisfy(message -> {
            assertThat(message.forwarded()).isTrue();
            assertThat(message.content()).contains("flaky on the redis tests");
        });
        assertThat(buildMessages).anySatisfy(message ->
            assertThat(message.reactions()).isNotEmpty());
        assertThat(messageService.searchMessages(build.id(), dannyId, "nightly", 10))
            .hasSize(1);

        ConversationSummaryResponse miraDanny = directWith(miraId, "danny");
        assertThat(messageService.getMessages(miraDanny.id(), miraId, 0, 50))
            .anySatisfy(message -> {
                assertThat(message.replyToId()).isNotNull();
                assertThat(message.replyToContent()).contains("empty states");
            });
        // Read state: danny read the thread, so mira's copies carry a receipt.
        assertThat(messageService.getMessages(miraDanny.id(), miraId, 0, 50))
            .filteredOn(message -> message.senderUsername().equals("mira"))
            .allSatisfy(message -> assertThat(message.readByCount()).isGreaterThan(0));

        ConversationSummaryResponse miraLena = directWith(miraId, "lena");
        assertThat(messageService.getMessages(miraLena.id(), miraId, 0, 50))
            .anySatisfy(message -> {
                assertThat(message.deleted()).isTrue();
                assertThat(message.content()).isEqualTo("This message was deleted.");
            })
            .anySatisfy(message -> {
                assertThat(message.edited()).isTrue();
                assertThat(message.content()).contains("12px for sections");
            });
    }

    @Test
    @DisplayName("settings differ between accounts, which is what makes the notification paths testable")
    void preferencesDifferBetweenAccounts() {
        assertThat(settingsFor("yuki").isNotificationsEnabled()).isFalse();
        assertThat(settingsFor("lena").getTheme()).isEqualTo("dark");
        assertThat(settingsFor("ravi").isNotifyMentions()).isFalse();

        // An untouched account keeps the entity defaults, so the three above are
        // seeded differences rather than every row being written by hand.
        UserSettingsEntity untouched = settingsFor("mira");
        assertThat(untouched.getTheme()).isEqualTo("light");
        assertThat(untouched.isNotificationsEnabled()).isTrue();
        assertThat(untouched.isNotifyMentions()).isTrue();
    }

    @Test
    @DisplayName("running the seed twice changes nothing")
    void seedingIsIdempotent() {
        int conversations = conversationService.getUserConversations(miraId).size();
        int friends = friendService.getFriends(miraId).size();
        int buildMessages = messageService.getMessages(groupByTitle(dannyId, "Build Failures").id(), dannyId, 0, 50).size();

        seeder.run(null);

        assertThat(conversationService.getUserConversations(miraId)).hasSize(conversations);
        assertThat(friendService.getFriends(miraId)).hasSize(friends);
        assertThat(messageService.getMessages(groupByTitle(dannyId, "Build Failures").id(), dannyId, 0, 50))
            .hasSize(buildMessages);
    }

    private ConversationSummaryResponse groupByTitle(UUID memberId, String title) {
        return conversationService.getUserConversations(memberId).stream()
            .filter(conversation -> conversation.isGroup() && title.equals(conversation.title()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Seeded group '" + title + "' not visible to " + memberId));
    }

    private ConversationSummaryResponse directWith(UUID userId, String otherUsername) {
        return conversationService.getUserConversations(userId).stream()
            .filter(conversation -> !conversation.isGroup() && otherUsername.equals(conversation.otherUsername()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Seeded direct chat with " + otherUsername + " missing"));
    }

    private UserSettingsEntity settingsFor(String username) {
        UUID userId = authService.login(new LoginRequest(username + "@seed.local", DataSeeder.PASSWORD), null).id();
        return settingsRepository.findByUserId(userId)
            .orElseThrow(() -> new AssertionError("No settings row for " + username));
    }
}
