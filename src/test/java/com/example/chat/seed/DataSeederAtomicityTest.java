package com.example.chat.seed;

import com.example.chat.application.AuthService;
import com.example.chat.application.ConversationService;
import com.example.chat.application.FriendService;
import com.example.chat.application.GroupInvitationService;
import com.example.chat.application.GroupInviteLinkService;
import com.example.chat.application.GroupJoinRequestService;
import com.example.chat.application.PersistentMessageService;
import com.example.chat.dto.auth.RegisterRequest;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failed seed must leave nothing behind.
 *
 * <p>The guard in {@link DataSeeder#run} answers "has this database been seeded?"
 * by looking for one account, so half-written rows are worse than no rows: they
 * make every later run skip, and the world stays broken with no way to finish it.
 * That is not hypothetical - promoting a moderator against a database whose role
 * column still had the pre-Flyway value list failed exactly there, after ten
 * accounts, seven friendships and one group were already committed.</p>
 *
 * <p>Runs with the seed flag off and builds the seeder by hand, because the point
 * is to make it fail against a database that is *not* already seeded - a context
 * that ran the runner at startup could not be put back into that state.</p>
 */
@SpringBootTest(properties = {
    "chat.seed.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:seed_atomic;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
})
class DataSeederAtomicityTest {

    @Autowired private AuthService authService;
    @Autowired private FriendService friendService;
    @Autowired private ConversationService conversationService;
    @Autowired private PersistentMessageService messageService;
    @Autowired private GroupJoinRequestService joinRequestService;
    @Autowired private GroupInvitationService invitationService;
    @Autowired private GroupInviteLinkService inviteLinkService;
    @Autowired private UserSettingsRepository settingsRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a seed that fails part-way through rolls the whole world back")
    void failureLeavesNoPartialWorld() {
        // Not "mira", so the guard lets the seed start; taken by the second
        // account, so it dies mid-way with the first account already inserted.
        authService.register(new RegisterRequest("danny", "danny@elsewhere.test", "someone-elses-password", "Real Danny"), null);

        DataSeeder seeder = new DataSeeder(authService, friendService, conversationService, messageService,
            joinRequestService, invitationService, inviteLinkService, settingsRepository,
            new TransactionTemplate(transactionManager));

        assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(ChatException.class);

        assertThat(authService.searchUsers("mira", 0, 1).getTotalElements()).isZero();
        assertThat(authService.searchUsers("lena", 0, 1).getTotalElements()).isZero();
    }
}
