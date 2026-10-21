package com.example.chat.application;

import com.example.chat.domain.entity.ConversationEntity;
import com.example.chat.domain.entity.ConversationMemberEntity;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.domain.entity.UserEntity;
import com.example.chat.domain.entity.UserSettingsEntity;
import com.example.chat.dto.notification.NotificationDto;
import com.example.chat.infrastructure.redis.DistributedMessageRelay;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.ConversationRepository;
import com.example.chat.infrastructure.repository.MessageRepository;
import com.example.chat.infrastructure.repository.UserRepository;
import com.example.chat.infrastructure.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pushes live notifications to the people they concern, after checking each
 * person's own preferences.
 *
 * <h3>Delivery</h3>
 * <p>Each user owns the topic {@code /topic/notifications/{userId}}. A
 * per-user <em>topic</em> rather than {@code /user/queue/...} is a deliberate
 * choice: {@link DistributedMessageRelay} only carries topic destinations
 * across cluster nodes, so a notification raised on node B still reaches a
 * client connected to node A. The topic is guarded by
 * {@code StompSubscriptionGuard}, which lets only the matching session
 * subscribe - the unguessable UUID in the path is a convenience, not the
 * authorisation.</p>
 *
 * <h3>Failure policy</h3>
 * <p>Notifications are a side effect of a business write that has already
 * succeeded, so nothing in here may fail that write. Lookups run in the
 * caller's transaction (plain reads of rows the caller already trusts - there
 * is no second transaction and therefore no new lock ordering), the frame is
 * published only after that transaction commits, and any exception is logged
 * and swallowed.</p>
 */
@Service
public class NotificationService {

    public static final String TYPE_NEW_MESSAGE = "NEW_MESSAGE";
    public static final String TYPE_MENTION = "MENTION";
    public static final String TYPE_REPLY = "REPLY";
    public static final String TYPE_FRIEND_REQUEST = "FRIEND_REQUEST";
    public static final String TYPE_FRIEND_ACCEPTED = "FRIEND_REQUEST_ACCEPTED";
    public static final String TYPE_GROUP_INVITE = "GROUP_INVITE";
    public static final String TYPE_JOIN_REQUEST = "GROUP_JOIN_REQUEST";
    public static final String TYPE_JOIN_ACCEPTED = "GROUP_JOIN_ACCEPTED";

    /** Notification kinds, mapped onto the four switches in user settings. */
    public enum Category {
        MESSAGE, MENTION, FRIEND_REQUEST, GROUP_ACTIVITY
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern MENTION_TOKEN = Pattern.compile("@([A-Za-z0-9_.\\-]{2,32})");
    private static final int PREVIEW_LENGTH = 120;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectProvider<DistributedMessageRelay> relayProvider;

    public NotificationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository memberRepository,
                               MessageRepository messageRepository,
                               UserRepository userRepository,
                               UserSettingsRepository settingsRepository,
                               SimpMessagingTemplate messagingTemplate,
                               ObjectProvider<DistributedMessageRelay> relayProvider) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.messagingTemplate = messagingTemplate;
        this.relayProvider = relayProvider;
    }

    /**
     * Notifies everyone in the conversation except the sender. A reader who is
     * named with {@code @handle} - or whose message is being replied to - is
     * told under the MENTION category instead, so muting ordinary group chatter
     * still lets a direct address through.
     */
    public void notifyNewMessage(UUID conversationId, UUID senderId, String senderUsername,
                                 UUID messageId, String content, UUID replyToId) {
        try {
            ConversationEntity conversation = conversationRepository.findById(conversationId).orElse(null);
            if (conversation == null) {
                return;
            }
            List<ConversationMemberEntity> members = memberRepository.findByConversationId(conversationId);
            Map<String, UUID> membersByHandle = handlesOf(members, senderId);
            Set<UUID> mentioned = mentionedIds(content, membersByHandle);
            UUID repliedUserId = replyToId == null ? null : messageRepository.findById(replyToId)
                .map(message -> message.getSenderId())
                .orElse(null);

            String title = conversationTitle(conversation, senderUsername);
            String preview = preview(content);

            for (ConversationMemberEntity member : members) {
                UUID recipient = member.getUserId();
                if (recipient.equals(senderId)) {
                    continue;
                }
                String type;
                Category category;
                if (mentioned.contains(recipient)) {
                    type = TYPE_MENTION;
                    category = Category.MENTION;
                } else if (recipient.equals(repliedUserId)) {
                    type = TYPE_REPLY;
                    category = Category.MENTION;
                } else {
                    type = TYPE_NEW_MESSAGE;
                    category = Category.MESSAGE;
                }
                deliver(recipient, category, type, senderId, senderUsername, conversationId, messageId,
                    title, preview);
            }
        } catch (Exception e) {
            log.warn("Could not compute message notifications for conversation {}", conversationId, e);
        }
    }

    public void notifyFriendRequest(UUID senderId, String senderUsername, UUID receiverId, String message) {
        try {
            String note = preview(message);
            String body = note != null ? note : senderUsername + " wants to connect with you";
            deliver(receiverId, Category.FRIEND_REQUEST, TYPE_FRIEND_REQUEST, senderId, senderUsername,
                null, null, "Friend request", body);
        } catch (Exception e) {
            log.warn("Could not notify {} about a friend request", receiverId, e);
        }
    }

    public void notifyFriendRequestAccepted(UUID accepterId, String accepterUsername, UUID originalSenderId) {
        try {
            deliver(originalSenderId, Category.FRIEND_REQUEST, TYPE_FRIEND_ACCEPTED, accepterId, accepterUsername,
                null, null, "Friend request accepted", accepterUsername + " accepted your friend request");
        } catch (Exception e) {
            log.warn("Could not notify {} about an accepted friend request", originalSenderId, e);
        }
    }

    public void notifyGroupInvitation(UUID inviterId, String inviterUsername, UUID invitedUserId,
                                      UUID groupId, String groupTitle) {
        try {
            deliver(invitedUserId, Category.GROUP_ACTIVITY, TYPE_GROUP_INVITE, inviterId, inviterUsername,
                groupId, null, groupTitle, inviterUsername + " invited you to join " + groupTitle);
        } catch (Exception e) {
            log.warn("Could not notify {} about a group invitation", invitedUserId, e);
        }
    }

    /** Tells the group's owners and moderators that somebody wants in. */
    public void notifyJoinRequest(UUID requesterId, String requesterUsername, UUID groupId, String groupTitle) {
        try {
            for (ConversationMemberEntity manager : managerMembers(groupId, requesterId)) {
                deliver(manager.getUserId(), Category.GROUP_ACTIVITY, TYPE_JOIN_REQUEST, requesterId,
                    requesterUsername, groupId, null, groupTitle,
                    requesterUsername + " asked to join " + groupTitle);
            }
        } catch (Exception e) {
            log.warn("Could not notify managers about a join request in group {}", groupId, e);
        }
    }

    public void notifyJoinAccepted(UUID userId, String actorUsername, UUID groupId, String groupTitle) {
        try {
            deliver(userId, Category.GROUP_ACTIVITY, TYPE_JOIN_ACCEPTED, null, actorUsername, groupId, null,
                groupTitle, "You joined " + groupTitle);
        } catch (Exception e) {
            log.warn("Could not notify {} about an accepted join request", userId, e);
        }
    }

    /**
     * Applies the recipient's own switches, then publishes. Returns false when
     * the recipient asked not to be told about this category.
     */
    private boolean deliver(UUID recipient, Category category, String type, UUID actorId, String actorUsername,
                            UUID conversationId, UUID messageId, String title, String body) {
        if (recipient == null || !isEnabled(recipient, category)) {
            return false;
        }
        NotificationDto payload = new NotificationDto(type, category.name(), actorId, actorUsername,
            conversationId, messageId, title, body, Instant.now());
        publishAfterCommit(MessageRouter.notificationTopic(recipient), payload);
        return true;
    }

    private boolean isEnabled(UUID userId, Category category) {
        UserSettingsEntity settings = settingsRepository.findByUserId(userId).orElse(null);
        if (settings == null) {
            // A user who has never opened the settings page gets the entity's
            // documented defaults: everything on.
            return true;
        }
        if (!settings.isNotificationsEnabled()) {
            return false;
        }
        return switch (category) {
            case MESSAGE -> settings.isNotifyMessages();
            case MENTION -> settings.isNotifyMentions();
            case FRIEND_REQUEST -> settings.isNotifyFriendRequests();
            case GROUP_ACTIVITY -> settings.isNotifyGroupActivity();
        };
    }

    /** handle (lower-cased) -> user id, for every member except the sender. */
    private Map<String, UUID> handlesOf(List<ConversationMemberEntity> members, UUID senderId) {
        Set<UUID> ids = new HashSet<>();
        members.forEach(member -> {
            if (!member.getUserId().equals(senderId)) {
                ids.add(member.getUserId());
            }
        });
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> byHandle = new HashMap<>();
        for (UserEntity user : userRepository.findAllById(ids)) {
            if (user.getUsername() != null) {
                byHandle.put(user.getUsername().toLowerCase(), user.getId());
            }
        }
        return byHandle;
    }

    private static Set<UUID> mentionedIds(String content, Map<String, UUID> membersByHandle) {
        if (content == null || content.indexOf('@') < 0 || membersByHandle.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        Matcher matcher = MENTION_TOKEN.matcher(content);
        while (matcher.find()) {
            UUID id = membersByHandle.get(matcher.group(1).toLowerCase());
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<ConversationMemberEntity> managerMembers(UUID groupId, UUID excludeUserId) {
        return memberRepository.findByConversationId(groupId).stream()
            .filter(member -> member.getRole() == MemberRole.OWNER || member.getRole() == MemberRole.MODERATOR)
            .filter(member -> !member.getUserId().equals(excludeUserId))
            .toList();
    }

    private static String conversationTitle(ConversationEntity conversation, String fallback) {
        if (conversation.getTitle() != null && !conversation.getTitle().isBlank()) {
            return conversation.getTitle();
        }
        return fallback == null ? "New message" : fallback;
    }

    private static String preview(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.length() <= PREVIEW_LENGTH ? trimmed : trimmed.substring(0, PREVIEW_LENGTH) + "...";
    }

    private void publishAfterCommit(String destination, NotificationDto payload) {
        Runnable publish = () -> {
            try {
                messagingTemplate.convertAndSend(destination, payload);
            } catch (Exception e) {
                log.warn("Could not deliver notification to {}", destination, e);
            }
            try {
                DistributedMessageRelay relay = relayProvider != null ? relayProvider.getIfAvailable() : null;
                if (relay != null) {
                    relay.publishCrossInstance(destination, payload);
                }
            } catch (Exception e) {
                log.warn("Could not relay notification to other instances", e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
