package com.example.chat.application;

import com.example.chat.domain.entity.UserEntity;
import com.example.chat.dto.auth.PublicProfileResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.FriendshipRepository;
import com.example.chat.infrastructure.repository.UserRepository;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads the profile card one account sees about another.
 *
 * <p>Separate from {@link AuthService} because that service answers "who am I"
 * and returns the email-bearing profile of the caller; nothing here may leak an
 * email address to a stranger. The two counts are aggregate numbers rather than
 * lists on purpose - a profile card should not become a way to enumerate
 * somebody's friends.</p>
 */
@Service
public class ProfileQueryService {

    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;
    private final FriendshipRepository friendshipRepository;
    private final ConversationMemberRepository memberRepository;
    private final PresenceService presenceService;
    private final FriendService friendService;

    public ProfileQueryService(UserRepository userRepository,
                               UserSettingsRepository settingsRepository,
                               FriendshipRepository friendshipRepository,
                               ConversationMemberRepository memberRepository,
                               PresenceService presenceService,
                               FriendService friendService) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.friendshipRepository = friendshipRepository;
        this.memberRepository = memberRepository;
        this.presenceService = presenceService;
        this.friendService = friendService;
    }

    @Transactional(readOnly = true)
    public PublicProfileResponse findProfile(UUID viewerId, UUID targetId) {
        UserEntity target = userRepository.findById(targetId)
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));

        // A deactivated account is reported the same way as a missing one: neither
        // confirms to the caller that the id used to belong to somebody.
        if (target.isDeactivated()) {
            throw new ChatException(ChatException.USER_NOT_FOUND, "User not found");
        }

        boolean sharesPresence = settingsRepository.findByUserId(targetId)
            .map(settings -> settings.isOnlineVisibility())
            .orElse(true);
        boolean online = sharesPresence && presenceService.isOnline(target.getUsername());

        boolean viewingSelf = viewerId.equals(targetId);
        String friendshipStatus = viewingSelf
            ? "SELF"
            : friendService.getFriendshipStatus(viewerId, targetId).status();
        int mutualFriends = viewingSelf ? 0 : (int) friendshipRepository.countMutualFriends(viewerId, targetId);
        int sharedGroups = viewingSelf ? 0 : (int) memberRepository.countSharedGroups(viewerId, targetId);

        return new PublicProfileResponse(
            target.getId(),
            target.getUsername(),
            target.getDisplayName(),
            target.getAvatarUrl(),
            target.getBio(),
            target.getCreatedAt(),
            online,
            sharesPresence,
            friendshipStatus,
            mutualFriends,
            sharedGroups
        );
    }
}
