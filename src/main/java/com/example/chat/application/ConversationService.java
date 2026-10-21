package com.example.chat.application;

import com.example.chat.domain.entity.*;
import com.example.chat.dto.conversation.*;
import com.example.chat.dto.group.BlockedUserResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

@Service
public class ConversationService {

    /**
     * Striped monitors guarding direct-conversation creation. A pair of users
     * maps to one stripe deterministically, so two threads opening the same
     * DM - from either side - always queue on the same object.
     */
    private static final int PAIR_LOCK_STRIPES = 64;

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final GroupBlockRepository groupBlockRepository;
    private final GroupJoinRequestRepository groupJoinRequestRepository;
    private final GroupInvitationRepository groupInvitationRepository;
    private final PresenceService presenceService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final Object[] pairLocks;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository memberRepository,
                               UserRepository userRepository,
                               MessageRepository messageRepository,
                               GroupBlockRepository groupBlockRepository,
                               GroupJoinRequestRepository groupJoinRequestRepository,
                               GroupInvitationRepository groupInvitationRepository,
                               PresenceService presenceService,
                               NotificationService notificationService,
                               PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.groupBlockRepository = groupBlockRepository;
        this.groupJoinRequestRepository = groupJoinRequestRepository;
        this.groupInvitationRepository = groupInvitationRepository;
        this.presenceService = presenceService;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.pairLocks = new Object[PAIR_LOCK_STRIPES];
        for (int i = 0; i < PAIR_LOCK_STRIPES; i++) {
            pairLocks[i] = new Object();
        }
    }

    /**
     * No conversation row exists yet to put a database lock on, so the
     * check-then-create of a DM is serialized on a striped JVM monitor and
     * the create runs inside a transaction that commits before the monitor is
     * released - the second thread's re-check then sees the first one's row.
     * Scope: per node; multi-node deployments can still occasionally mint a
     * duplicate DM (cosmetic - both stay usable, nothing is lost).
     */
    public ConversationSummaryResponse getOrCreateDirect(UUID currentUserId, UUID targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot start a conversation with yourself");
        }

        synchronized (pairLockFor(currentUserId, targetUserId)) {
            return transactionTemplate.execute(status ->
                getOrCreateDirectInTx(currentUserId, targetUserId));
        }
    }

    private ConversationSummaryResponse getOrCreateDirectInTx(UUID currentUserId, UUID targetUserId) {
        UserEntity targetUser = userRepository.findById(targetUserId)
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "Target user not found"));

        Optional<ConversationEntity> existing = conversationRepository.findDirectConversationBetween(currentUserId, targetUserId);
        if (existing.isPresent()) {
            ConversationEntity conv = existing.get();
            // Un-archive if previously archived
            memberRepository.findByConversationIdAndUserId(conv.getId(), currentUserId).ifPresent(m -> {
                if (m.isArchived()) {
                    m.setArchived(false);
                    memberRepository.save(m);
                }
            });
            return mapToSummary(conv, currentUserId);
        }

        ConversationEntity conv = new ConversationEntity(ConversationType.DIRECT, null, currentUserId);
        conv = conversationRepository.save(conv);

        ConversationMemberEntity member1 = new ConversationMemberEntity(conv.getId(), currentUserId, MemberRole.MEMBER);
        ConversationMemberEntity member2 = new ConversationMemberEntity(conv.getId(), targetUserId, MemberRole.MEMBER);
        memberRepository.saveAll(List.of(member1, member2));

        return mapToSummary(conv, currentUserId);
    }

    private Object pairLockFor(UUID a, UUID b) {
        int lo = a.compareTo(b) < 0 ? a.hashCode() : b.hashCode();
        int hi = a.compareTo(b) < 0 ? b.hashCode() : a.hashCode();
        return pairLocks[Math.floorMod(31 * lo + hi, PAIR_LOCK_STRIPES)];
    }

    @Transactional
    public ConversationSummaryResponse createGroup(UUID currentUserId, CreateGroupRequest request) {
        String title = request.title().trim();
        ConversationEntity group = new ConversationEntity(ConversationType.GROUP, title, currentUserId);
        if (request.description() != null) group.setDescription(request.description().trim());
        if (request.avatarUrl() != null) group.setAvatarUrl(request.avatarUrl().trim());
        group.setPrivate(Boolean.TRUE.equals(request.isPrivate()));
        group = conversationRepository.save(group);

        List<ConversationMemberEntity> members = new ArrayList<>();
        members.add(new ConversationMemberEntity(group.getId(), currentUserId, MemberRole.OWNER));

        if (request.memberIds() != null) {
            for (UUID memberId : request.memberIds()) {
                if (!memberId.equals(currentUserId) && userRepository.existsById(memberId)) {
                    members.add(new ConversationMemberEntity(group.getId(), memberId, MemberRole.MEMBER));
                }
            }
        }
        memberRepository.saveAll(members);
        return mapToSummary(group, currentUserId);
    }

    /**
     * Direct entry into a public group. Private groups require an approved join
     * request or an accepted invitation instead.
     */
    @Transactional
    public ConversationSummaryResponse joinGroup(UUID conversationId, UUID currentUserId) {
        // Row lock: the exists-check below is only meaningful if competing
        // joins for this group serialize here instead of both inserting and
        // one colliding with uq_conv_member at commit.
        ConversationEntity conv = conversationRepository.findByIdForUpdate(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));

        if (conv.getType() != ConversationType.GROUP) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Can only join group conversations");
        }
        if (groupBlockRepository.existsByGroupIdAndUserId(conversationId, currentUserId)) {
            throw new ChatException(ChatException.USER_BLOCKED, "You are blocked from this group");
        }
        if (memberRepository.existsByConversationIdAndUserId(conversationId, currentUserId)) {
            return mapToSummary(conv, currentUserId);
        }
        if (conv.isPrivate()) {
            throw new ChatException(ChatException.FORBIDDEN, "This group is private. Send a join request instead.");
        }

        memberRepository.save(new ConversationMemberEntity(conversationId, currentUserId, MemberRole.MEMBER));
        return mapToSummary(conv, currentUserId);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> getUserConversations(UUID currentUserId) {
        List<ConversationEntity> convs = conversationRepository.findActiveConversationsForUser(currentUserId);
        return convs.stream().map(c -> mapToSummary(c, currentUserId)).toList();
    }

    @Transactional(readOnly = true)
    public ConversationSummaryResponse getConversationDetails(UUID conversationId, UUID currentUserId) {
        requireMembership(conversationId, currentUserId);
        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));
        return mapToSummary(conv, currentUserId);
    }

    @Transactional(readOnly = true)
    public List<ConversationMemberResponse> getConversationMembers(UUID conversationId, UUID currentUserId) {
        requireMembership(conversationId, currentUserId);
        List<ConversationMemberEntity> members = memberRepository.findByConversationId(conversationId);

        List<ConversationMemberResponse> result = new ArrayList<>();
        for (ConversationMemberEntity m : members) {
            userRepository.findById(m.getUserId()).ifPresent(user -> {
                boolean online = presenceService.isOnline(user.getUsername());
                result.add(new ConversationMemberResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getAvatarUrl(),
                    m.getRole(),
                    m.getJoinedAt(),
                    online
                ));
            });
        }
        return result;
    }

    @Transactional
    public void addMember(UUID conversationId, UUID currentUserId, AddMemberRequest request) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        ConversationEntity conv = conversationRepository.findByIdForUpdate(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        if (conv.getType() == ConversationType.DIRECT) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot add members to a direct conversation");
        }

        if (currentMember.getRole() == MemberRole.MEMBER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can add members directly");
        }

        if (memberRepository.existsByConversationIdAndUserId(conversationId, request.userId())) {
            return; // already member
        }

        if (groupBlockRepository.existsByGroupIdAndUserId(conversationId, request.userId())) {
            throw new ChatException(ChatException.USER_BLOCKED, "This user is blocked from the group. Unblock them first.");
        }

        if (!userRepository.existsById(request.userId())) {
            throw new ChatException(ChatException.USER_NOT_FOUND, "User not found");
        }

        ConversationMemberEntity newMember = new ConversationMemberEntity(conversationId, request.userId(), MemberRole.MEMBER);
        memberRepository.save(newMember);

        // Being added is invisible until somebody tells you: the new member
        // gets the same group-invitation notification an invite produces.
        UserEntity admin = userRepository.findById(currentUserId).orElse(null);
        if (admin != null) {
            notificationService.notifyGroupInvitation(admin.getId(), admin.getUsername(),
                request.userId(), conv.getId(), conv.getTitle());
        }
    }

    @Transactional
    public void removeMember(UUID conversationId, UUID currentUserId, UUID memberIdToRemove) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        if (conv.getType() == ConversationType.DIRECT) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot remove members from a direct conversation");
        }

        // Self-removal is leaving; owners may remove anyone; moderators only plain members.
        boolean isSelf = currentUserId.equals(memberIdToRemove);

        if (!isSelf) {
            ConversationMemberEntity target = memberRepository.findByConversationIdAndUserId(conversationId, memberIdToRemove)
                .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "That user is not a member of this group"));

            if (currentMember.getRole() == MemberRole.OWNER) {
                // allowed
            } else if (currentMember.getRole() == MemberRole.MODERATOR && target.getRole() == MemberRole.MEMBER) {
                // allowed
            } else {
                throw new ChatException(ChatException.FORBIDDEN, "You are not allowed to remove this member");
            }
        }

        if (isSelf && currentMember.getRole() == MemberRole.OWNER) {
            List<ConversationMemberEntity> remaining = memberRepository.findByConversationId(conversationId)
                .stream().filter(m -> !m.getUserId().equals(currentUserId)).toList();
            if (remaining.isEmpty()) {
                memberRepository.deleteByConversationId(conversationId);
                messageRepository.deleteByConversationId(conversationId);
                conversationRepository.deleteById(conversationId);
                return;
            } else {
                ConversationMemberEntity newOwner = remaining.get(0);
                newOwner.setRole(MemberRole.OWNER);
                memberRepository.save(newOwner);
                conv.setCreatedBy(newOwner.getUserId());
                conversationRepository.save(conv);
            }
        }

        memberRepository.deleteByConversationIdAndUserId(conversationId, memberIdToRemove);
    }

    @Transactional
    public ConversationSummaryResponse updateGroup(UUID conversationId, UUID currentUserId, UpdateGroupRequest request) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        if (currentMember.getRole() == MemberRole.MEMBER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can update group details");
        }

        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        conv.setTitle(request.title().trim());
        if (request.description() != null) conv.setDescription(request.description().trim());
        if (request.avatarUrl() != null) conv.setAvatarUrl(request.avatarUrl().trim());
        if (request.isPrivate() != null) conv.setPrivate(request.isPrivate());
        conv = conversationRepository.save(conv);

        return mapToSummary(conv, currentUserId);
    }

    @Transactional
    public void deleteGroup(UUID conversationId, UUID currentUserId) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        if (currentMember.getRole() != MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group owner can delete the group");
        }
        memberRepository.deleteByConversationId(conversationId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    @Transactional
    public void archiveConversation(UUID conversationId, UUID currentUserId, boolean archive) {
        ConversationMemberEntity member = requireMembership(conversationId, currentUserId);
        member.setArchived(archive);
        memberRepository.save(member);
    }

    /** Owners promote members to moderator and demote moderators back. */
    @Transactional
    public ConversationMemberResponse setMemberRole(UUID conversationId, UUID currentUserId, UUID targetUserId, MemberRole newRole) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        if (currentMember.getRole() != MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only the group owner can change roles");
        }
        if (newRole == MemberRole.OWNER) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Ownership is transferred by leaving the group");
        }

        ConversationMemberEntity target = memberRepository.findByConversationIdAndUserId(conversationId, targetUserId)
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "That user is not a member of this group"));

        if (target.getRole() == MemberRole.OWNER) {
            throw new ChatException(ChatException.INVALID_COMMAND, "The group owner's role cannot be changed");
        }

        target.setRole(newRole);
        memberRepository.save(target);
        UserEntity user = userRepository.findById(targetUserId).orElseThrow();
        return new ConversationMemberResponse(
            user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl(),
            target.getRole(), target.getJoinedAt(), presenceService.isOnline(user.getUsername()));
    }

    /**
     * Admins block a user from the group: membership and any pending join
     * request or invitation are removed and the user cannot rejoin or be
     * invited again until unblocked.
     */
    @Transactional
    public void blockMember(UUID conversationId, UUID currentUserId, UUID targetUserId) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        boolean isAdmin = currentMember.getRole() == MemberRole.OWNER || currentMember.getRole() == MemberRole.MODERATOR;
        if (!isAdmin) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can block members");
        }
        if (currentUserId.equals(targetUserId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "You cannot block yourself");
        }

        ConversationMemberEntity target = memberRepository.findByConversationIdAndUserId(conversationId, targetUserId)
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "That user is not a member of this group"));
        if (target.getRole() == MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "The group owner cannot be blocked");
        }
        if (currentMember.getRole() == MemberRole.MODERATOR && target.getRole() != MemberRole.MEMBER) {
            throw new ChatException(ChatException.FORBIDDEN, "Moderators can only block plain members");
        }

        memberRepository.delete(target);
        groupJoinRequestRepository.deleteByGroupIdAndUserId(conversationId, targetUserId);
        groupInvitationRepository.findByGroupIdAndInvitedUserId(conversationId, targetUserId)
            .ifPresent(invitation -> {
                if (invitation.getStatus() == GroupInvitationStatus.PENDING) {
                    groupInvitationRepository.delete(invitation);
                }
            });
        if (!groupBlockRepository.existsByGroupIdAndUserId(conversationId, targetUserId)) {
            groupBlockRepository.save(new GroupBlockEntity(conversationId, targetUserId, currentUserId));
        }
    }

    @Transactional
    public void unblockMember(UUID conversationId, UUID currentUserId, UUID targetUserId) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        boolean isAdmin = currentMember.getRole() == MemberRole.OWNER || currentMember.getRole() == MemberRole.MODERATOR;
        if (!isAdmin) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can unblock users");
        }
        if (!groupBlockRepository.existsByGroupIdAndUserId(conversationId, targetUserId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "That user is not blocked from this group");
        }
        groupBlockRepository.deleteByGroupIdAndUserId(conversationId, targetUserId);
    }

    @Transactional(readOnly = true)
    public List<BlockedUserResponse> getBlockedMembers(UUID conversationId, UUID currentUserId) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        boolean isAdmin = currentMember.getRole() == MemberRole.OWNER || currentMember.getRole() == MemberRole.MODERATOR;
        if (!isAdmin) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can view blocked users");
        }
        List<BlockedUserResponse> blocked = new ArrayList<>();
        for (GroupBlockEntity block : groupBlockRepository.findByGroupId(conversationId)) {
            userRepository.findById(block.getUserId()).ifPresent(user -> blocked.add(new BlockedUserResponse(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl(), block.getCreatedAt())));
        }
        return blocked;
    }

    public ConversationMemberEntity requireMembership(UUID conversationId, UUID userId) {
        return memberRepository.findByConversationIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this conversation"));
    }

    private ConversationSummaryResponse mapToSummary(ConversationEntity conv, UUID currentUserId) {
        String title = conv.getTitle();
        String avatar = conv.getAvatarUrl();
        String otherUsername = null;
        UUID otherUserId = null;

        if (conv.getType() == ConversationType.DIRECT) {
            List<ConversationMemberEntity> members = memberRepository.findByConversationId(conv.getId());
            for (ConversationMemberEntity m : members) {
                if (!m.getUserId().equals(currentUserId)) {
                    Optional<UserEntity> other = userRepository.findById(m.getUserId());
                    if (other.isPresent()) {
                        title = other.get().getDisplayName();
                        otherUsername = other.get().getUsername();
                        otherUserId = other.get().getId();
                        avatar = other.get().getAvatarUrl();
                    }
                    break;
                }
            }
        }

        long unread = messageRepository.countUnreadMessages(conv.getId(), currentUserId);

        return new ConversationSummaryResponse(
            conv.getId(),
            conv.getType(),
            title,
            conv.getDescription(),
            avatar,
            conv.getCreatedBy(),
            conv.getLastMessageText(),
            conv.getLastMessageAt(),
            conv.getLastMessageSender(),
            unread,
            conv.getType() == ConversationType.GROUP,
            conv.isPrivate(),
            otherUsername,
            otherUserId
        );
    }
}
