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
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.dto.auth.UpdateProfileRequest;
import com.example.chat.dto.auth.UserProfileResponse;
import com.example.chat.dto.conversation.ConversationSummaryResponse;
import com.example.chat.dto.conversation.CreateGroupRequest;
import com.example.chat.dto.friend.SendFriendRequest;
import com.example.chat.dto.group.CreateInviteLinkRequest;
import com.example.chat.dto.group.InviteToGroupRequest;
import com.example.chat.dto.message.PersistentMessageResponse;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * Fills an empty database with a small, connected demo world: ten accounts,
 * their friendships, three groups, direct conversations, and messages carrying
 * replies, reactions, pins, an edit, a soft delete, a forward and read state.
 *
 * <h3>It never runs unless you ask for it</h3>
 * <p>The bean exists only when {@code chat.seed.enabled=true}, which is neither
 * the default in {@code application.yml} nor set by any test other than
 * {@code DataSeederTest}. A deployment that does not opt in does not contain
 * this code path at all, because {@link ConditionalOnProperty} keeps the bean
 * out of the context — there is no "oops, we shipped demo users to production"
 * failure mode here. The same flag re-run against a database that already holds
 * the seed is a no-op, so restarting a seeded dev instance is safe.</p>
 *
 * <h3>Why it calls the services instead of writing rows</h3>
 * <p>Every record arrives through the same path a browser uses: BCrypt hashing,
 * paired friendship rows, membership rows with roles, after-commit broadcasts,
 * idempotent sends. SQL would produce a database that looks right and behaves
 * wrong, and the point of seeded data is to exercise the real thing. The
 * trade-off is that seeding is rate-limited like any other client — which is
 * why the dataset stays well inside 60 messages per sender.</p>
 */
@Component
@ConditionalOnProperty(name = "chat.seed.enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    /**
     * One shared password for all seeded accounts. These are development
     * fixtures on a throwaway database, not credentials: the usernames and the
     * {@code .seed.local} domain (unsolvable by design) mark the rows as
     * synthetic, and this constant is what makes the dataset usable at all.
     */
    public static final String PASSWORD = "seedpass123";

    private record Person(String username, String displayName, String bio) {}

    private record Friendship(String from, String to, String message) {}

    private static final List<Person> PEOPLE = List.of(
        new Person("mira", "Mira Kessler", "Keeps the roadmap honest."),
        new Person("danny", "Danny Okafor", "Builds the things nobody wants to build."),
        new Person("lena", "Lena Vasquez", "Design, type, and strong opinions about spacing."),
        new Person("tomas", "Tomas Lindqvist", "On call this week, unfortunately."),
        new Person("sara", "Sara Haddad", "Climbs on weekends, ships on weekdays."),
        new Person("owen", "Owen Bright", "Answers in paragraphs."),
        new Person("ravi", "Ravi Menon", "Quietly fixes the build."),
        new Person("yuki", "Yuki Tanaka", "Runs the retro, hates running the retro."),
        new Person("nadia", "Nadia Petrova", "Asks the question the meeting was avoiding."),
        new Person("phil", "Phil Moreau", "New here, still finding the channels.")
    );

    /** Accepted: sent by {@code from}, accepted by {@code to}. */
    private static final List<Friendship> FRIENDSHIPS = List.of(
        new Friendship("mira", "danny", "Long standups are shorter with you."),
        new Friendship("mira", "lena", "Design reviews count as friendship."),
        new Friendship("mira", "sara", ""),
        new Friendship("danny", "tomas", "Fellow on-call."),
        new Friendship("sara", "owen", ""),
        new Friendship("yuki", "ravi", ""),
        new Friendship("lena", "nadia", "")
    );

    /** Deliberately unanswered so the request inbox has something in it. */
    private static final List<Friendship> PENDING_REQUESTS = List.of(
        new Friendship("tomas", "nadia", "Want the pipeline notes?"),
        new Friendship("phil", "mira", "New to the team.")
    );

    private final AuthService authService;
    private final FriendService friendService;
    private final ConversationService conversationService;
    private final PersistentMessageService messageService;
    private final GroupJoinRequestService joinRequestService;
    private final GroupInvitationService invitationService;
    private final GroupInviteLinkService inviteLinkService;
    private final UserSettingsRepository settingsRepository;
    private final TransactionTemplate transactionTemplate;

    public DataSeeder(AuthService authService,
                      FriendService friendService,
                      ConversationService conversationService,
                      PersistentMessageService messageService,
                      GroupJoinRequestService joinRequestService,
                      GroupInvitationService invitationService,
                      GroupInviteLinkService inviteLinkService,
                      UserSettingsRepository settingsRepository,
                      TransactionTemplate transactionTemplate) {
        this.authService = authService;
        this.friendService = friendService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.joinRequestService = joinRequestService;
        this.invitationService = invitationService;
        this.inviteLinkService = inviteLinkService;
        this.settingsRepository = settingsRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (authService.searchUsers(PEOPLE.get(0).username(), 0, 1).getTotalElements() > 0) {
            log.info("Seed skipped: '{}' already exists, so this database has been seeded", PEOPLE.get(0).username());
            return;
        }

        // The whole world is one transaction. Seeded rows are individually valid
        // only as a set - accounts without friendships, or a group whose moderator
        // promotion failed, is a database the guard above then mistakes for
        // "already seeded" and can never be completed.
        transactionTemplate.executeWithoutResult(status -> seed());

        log.info("Seeded {} accounts sharing DataSeeder.PASSWORD, {} friendships plus {} pending requests, "
                + "three groups, direct chats and messages exercising replies, reactions, pins, an edit, "
                + "a soft delete and a forward",
            PEOPLE.size(), FRIENDSHIPS.size(), PENDING_REQUESTS.size());
    }

    private void seed() {
        UserSet world = new UserSet(createAccounts());
        createFriendships(world);
        varyPreferences(world);

        ConversationSummaryResponse designGroup = conversationService.createGroup(world.mira.id(),
            new CreateGroupRequest("Design Standup", "Fifteen minutes, every morning.", null,
                List.of(world.danny.id(), world.lena.id(), world.tomas.id()), false));
        conversationService.setMemberRole(designGroup.id(), world.mira.id(), world.lena.id(), MemberRole.MODERATOR);
        joinRequestService.requestToJoin(world.sara.id(), designGroup.id());

        ConversationSummaryResponse climbingGroup = conversationService.createGroup(world.sara.id(),
            new CreateGroupRequest("Weekend Climbing", "Private: route beta and meeting times.", null,
                List.of(world.owen.id(), world.yuki.id()), true));
        invitationService.inviteUsers(world.sara.id(), climbingGroup.id(),
            new InviteToGroupRequest(List.of(world.ravi.id())));

        // Blocking removes a member, so the blocked account has to join first.
        ConversationSummaryResponse buildGroup = conversationService.createGroup(world.tomas.id(),
            new CreateGroupRequest("Build Failures", "Red pipelines get triaged here.", null,
                List.of(world.danny.id(), world.ravi.id(), world.phil.id()), false));
        conversationService.blockMember(buildGroup.id(), world.tomas.id(), world.phil.id());

        ConversationSummaryResponse miraDanny = conversationService.getOrCreateDirect(world.mira.id(), world.danny.id());
        ConversationSummaryResponse miraLena = conversationService.getOrCreateDirect(world.mira.id(), world.lena.id());
        conversationService.getOrCreateDirect(world.sara.id(), world.owen.id());
        conversationService.getOrCreateDirect(world.yuki.id(), world.ravi.id());

        writeMessages(world, designGroup, climbingGroup, buildGroup, miraDanny, miraLena);
        createInviteLink(world, designGroup);
        applyReadState(world, designGroup, miraDanny, miraLena);
    }

    /** Everything the world needs by name instead of by index. */
    private static final class UserSet {
        private final List<UserProfileResponse> users;
        final UserProfileResponse mira;
        final UserProfileResponse danny;
        final UserProfileResponse lena;
        final UserProfileResponse tomas;
        final UserProfileResponse sara;
        final UserProfileResponse owen;
        final UserProfileResponse ravi;
        final UserProfileResponse yuki;
        final UserProfileResponse nadia;
        final UserProfileResponse phil;

        UserSet(List<UserProfileResponse> users) {
            this.users = List.copyOf(users);
            mira = byUsername("mira");
            danny = byUsername("danny");
            lena = byUsername("lena");
            tomas = byUsername("tomas");
            sara = byUsername("sara");
            owen = byUsername("owen");
            ravi = byUsername("ravi");
            yuki = byUsername("yuki");
            nadia = byUsername("nadia");
            phil = byUsername("phil");
        }

        UserProfileResponse byUsername(String username) {
            return users.stream().filter(user -> user.username().equals(username)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed account " + username + " was not created"));
        }
    }

    private List<UserProfileResponse> createAccounts() {
        List<UserProfileResponse> created = new ArrayList<>();
        for (Person person : PEOPLE) {
            UserProfileResponse user = authService.register(
                new RegisterRequest(person.username(), person.username() + "@seed.local", PASSWORD, person.displayName()),
                null);
            created.add(profile(user, person.bio()));
        }
        return created;
    }

    private void createFriendships(UserSet world) {
        for (Friendship friendship : FRIENDSHIPS) {
            UserProfileResponse from = world.byUsername(friendship.from());
            UserProfileResponse to = world.byUsername(friendship.to());
            var sent = friendService.sendFriendRequest(from.id(),
                new SendFriendRequest(to.username(), to.id(), friendship.message()));
            friendService.acceptFriendRequest(to.id(), sent.id());
        }
        for (Friendship pending : PENDING_REQUESTS) {
            UserProfileResponse from = world.byUsername(pending.from());
            UserProfileResponse to = world.byUsername(pending.to());
            friendService.sendFriendRequest(from.id(),
                new SendFriendRequest(to.username(), to.id(), pending.message()));
        }
    }

    private void varyPreferences(UserSet world) {
        // Preferences are varied so the settings screen and notification gating
        // have something to show; a uniform dataset proves nothing.
        silenceNotifications(world.yuki);
        UserSettingsEntity lenaSettings = settings(world.lena);
        lenaSettings.setTheme("dark");
        settingsRepository.save(lenaSettings);
        UserSettingsEntity raviSettings = settings(world.ravi);
        raviSettings.setNotifyMentions(false);
        settingsRepository.save(raviSettings);
    }

    private UserProfileResponse profile(UserProfileResponse user, String bio) {
        Principal as = () -> user.username();
        return authService.updateProfile(as, new UpdateProfileRequest(user.displayName(), bio, null));
    }

    private UserSettingsEntity settings(UserProfileResponse user) {
        return settingsRepository.findByUserId(user.id())
            .orElseThrow(() -> new IllegalStateException("Registration created no settings row for " + user.username()));
    }

    private void silenceNotifications(UserProfileResponse user) {
        UserSettingsEntity entity = settings(user);
        entity.setNotificationsEnabled(false);
        settingsRepository.save(entity);
    }

    private void writeMessages(UserSet world,
                                     ConversationSummaryResponse designGroup,
                                     ConversationSummaryResponse climbingGroup,
                                     ConversationSummaryResponse buildGroup,
                                     ConversationSummaryResponse miraDanny,
                                     ConversationSummaryResponse miraLena) {
        PersistentMessageResponse anchor = messageService.sendMessage(miraDanny.id(), world.mira.id(),
            world.mira.username(), "Did the header rework land in the branch?");
        PersistentMessageResponse answer = messageService.sendMessage(miraDanny.id(), world.danny.id(),
            world.danny.username(), "Yes, plus the empty states. Nothing left but the copy.");
        messageService.sendMessage(miraDanny.id(), world.mira.id(),
            world.mira.username(), "Copy is mine. Give me until tomorrow.", answer.id(), null);
        messageService.toggleReaction(answer.id(), world.mira.id(), "👍");
        messageService.toggleReaction(anchor.id(), world.danny.id(), "🎉");
        // Sent in a DM and forwarded into a group below: forwarding into the
        // conversation a message already lives in is correctly refused.
        PersistentMessageResponse flake = messageService.sendMessage(miraDanny.id(), world.danny.id(),
            world.danny.username(), "Integration suite is flaky on the redis tests again.");

        PersistentMessageResponse toEdit = messageService.sendMessage(miraLena.id(), world.lena.id(),
            world.lena.username(), "The spacing token is 8px");
        messageService.editMessage(toEdit.id(), world.lena.id(), "The spacing token is 8px, and 12px for sections");

        PersistentMessageResponse toDelete = messageService.sendMessage(miraLena.id(), world.lena.id(),
            world.lena.username(), "Ignore that last one, wrong number");
        messageService.deleteMessage(toDelete.id(), world.lena.id());

        messageService.sendMessage(designGroup.id(), world.mira.id(), world.mira.username(),
            "Standup in five. One topic each, no slides.");
        PersistentMessageResponse pinned = messageService.sendMessage(designGroup.id(), world.lena.id(),
            world.lena.username(), "Notes live in the shared doc, link is in the group description.");
        messageService.setPinned(pinned.id(), world.mira.id(), true);
        messageService.sendMessage(designGroup.id(), world.tomas.id(), world.tomas.username(),
            "I am on call, so I will drift in and out.");
        messageService.toggleReaction(pinned.id(), world.danny.id(), "📌");

        messageService.sendMessage(climbingGroup.id(), world.sara.id(), world.sara.username(),
            "Saturday 9am at the north wall. Two of the routes are still wet.");
        messageService.sendMessage(climbingGroup.id(), world.owen.id(), world.owen.username(),
            "I can bring the guide book this time.");

        PersistentMessageResponse pipeline = messageService.sendMessage(buildGroup.id(), world.tomas.id(),
            world.tomas.username(), "Pipeline is red on main and nobody has said so yet.");
        messageService.toggleReaction(pipeline.id(), world.ravi.id(), "👀");
        messageService.sendMessage(buildGroup.id(), world.ravi.id(), world.ravi.username(),
            "Retrying the flaky ones got us green, but that is not a fix.");

        // A forward across conversations, so the feature has a real example
        // rather than only a test.
        messageService.forwardMessage(buildGroup.id(), world.danny.id(),
            world.danny.username(), flake.id(), null);
        messageService.sendMessage(buildGroup.id(), world.danny.id(), world.danny.username(),
            "Forwarding that for context: the same flake bit the nightly.");
    }

    private void createInviteLink(UserSet world, ConversationSummaryResponse designGroup) {
        // Seven days, fifty joins: a link that a reader can actually try.
        inviteLinkService.create(world.mira.id(), designGroup.id(),
            new CreateInviteLinkRequest(604_800L, 50));
    }

    private void applyReadState(UserSet world,
                                ConversationSummaryResponse designGroup,
                                ConversationSummaryResponse miraDanny,
                                ConversationSummaryResponse miraLena) {
        messageService.markMessagesRead(miraDanny.id(), world.danny.id());
        messageService.markMessagesRead(miraLena.id(), world.mira.id());
        // Only some members read the group, so unread badges differ per person
        // instead of every conversation looking identical.
        messageService.markMessagesRead(designGroup.id(), world.danny.id());
        messageService.markMessagesRead(designGroup.id(), world.tomas.id());
    }
}
