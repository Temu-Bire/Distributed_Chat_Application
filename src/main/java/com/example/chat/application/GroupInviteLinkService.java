package com.example.chat.application;

import com.example.chat.domain.entity.ConversationEntity;
import com.example.chat.domain.entity.ConversationMemberEntity;
import com.example.chat.domain.entity.ConversationType;
import com.example.chat.domain.entity.GroupInviteLinkEntity;
import com.example.chat.domain.entity.GroupJoinRequestStatus;
import com.example.chat.domain.entity.MemberRole;
import com.example.chat.dto.group.CreateInviteLinkRequest;
import com.example.chat.dto.group.GroupInviteLinkResponse;
import com.example.chat.dto.group.InvitePreviewResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.ConversationMemberRepository;
import com.example.chat.infrastructure.repository.ConversationRepository;
import com.example.chat.infrastructure.repository.GroupBlockRepository;
import com.example.chat.infrastructure.repository.GroupInviteLinkRepository;
import com.example.chat.infrastructure.repository.GroupJoinRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shareable "anybody with this link can join" group invitations.
 *
 * <p>The token <em>is</em> the capability: whoever holds it can join the group
 * it points at, including a private group, which is exactly why links carry an
 * optional expiry and use budget and can be revoked. Tokens are 128 random
 * bits from {@link SecureRandom}, rendered as 32 lowercase hex characters, so
 * guessing one is not a realistic attack; the lookup path still validates the
 * shape first so junk never reaches the database.</p>
 *
 * <p>Joining locks the group row and then the link row. Both are needed - the
 * group keeps the membership insert atomic against admin adds and join
 * requests, the link keeps {@code usedCount} from overspending its budget when
 * two visitors arrive at the same instant.</p>
 */
@Service
public class GroupInviteLinkService {

    private static final int TOKEN_BYTES = 16;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[0-9a-f]{32}$");

    /** Every unusable token answers the same way; see {@link InvitePreviewResponse}. */
    private static final String INVALID = "INVALID";

    private final GroupInviteLinkRepository linkRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final GroupBlockRepository groupBlockRepository;
    private final GroupJoinRequestRepository joinRequestRepository;
    private final SecureRandom random = new SecureRandom();

    public GroupInviteLinkService(GroupInviteLinkRepository linkRepository,
                                  ConversationRepository conversationRepository,
                                  ConversationMemberRepository memberRepository,
                                  GroupBlockRepository groupBlockRepository,
                                  GroupJoinRequestRepository joinRequestRepository) {
        this.linkRepository = linkRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.groupBlockRepository = groupBlockRepository;
        this.joinRequestRepository = joinRequestRepository;
    }

    @Transactional
    public GroupInviteLinkResponse create(UUID userId, UUID groupId, CreateInviteLinkRequest request) {
        ConversationEntity group = requireGroup(groupId);
        requireAdmin(groupId, userId);

        Instant expiresAt = request != null && request.expiresInSeconds() != null
            ? Instant.now().plusSeconds(request.expiresInSeconds())
            : null;
        Integer maxUses = request == null ? null : request.maxUses();

        GroupInviteLinkEntity link = linkRepository.save(
            new GroupInviteLinkEntity(groupId, userId, randomToken(), expiresAt, maxUses));
        return map(link, group);
    }

    @Transactional(readOnly = true)
    public List<GroupInviteLinkResponse> listActive(UUID userId, UUID groupId) {
        ConversationEntity group = requireGroup(groupId);
        requireAdmin(groupId, userId);
        return linkRepository.findByGroupIdAndRevokedFalseOrderByCreatedAtDesc(groupId).stream()
            .map(link -> map(link, group))
            .toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID groupId, UUID linkId) {
        requireGroup(groupId);
        requireAdmin(groupId, userId);

        GroupInviteLinkEntity link = linkRepository.findById(linkId)
            .orElseThrow(() -> new ChatException(ChatException.INVALID_COMMAND, "Invite link not found"));
        if (!link.getGroupId().equals(groupId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Invite link not found");
        }
        if (link.isRevoked()) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Invite link is already revoked");
        }
        link.setRevoked(true);
        linkRepository.save(link);
    }

    /**
     * Describe a token before anyone commits to joining it. A logged-out or
     * uninvolved visitor learns nothing beyond the group's public card, and
     * only when the link is genuinely still usable for them.
     */
    @Transactional(readOnly = true)
    public InvitePreviewResponse preview(UUID userId, String token) {
        if (!isWellFormed(token)) {
            return new InvitePreviewResponse(null, null, null, null, 0, INVALID);
        }
        Optional<GroupInviteLinkEntity> found = linkRepository.findByToken(token.toLowerCase());
        if (found.isEmpty()) {
            return new InvitePreviewResponse(null, null, null, null, 0, INVALID);
        }
        GroupInviteLinkEntity link = found.get();
        ConversationEntity group = conversationRepository.findById(link.getGroupId()).orElse(null);
        if (group == null || group.getType() != ConversationType.GROUP || !link.isActiveAt(Instant.now())) {
            return new InvitePreviewResponse(null, null, null, null, 0, INVALID);
        }

        int memberCount = memberRepository.findByConversationId(group.getId()).size();
        if (memberRepository.existsByConversationIdAndUserId(group.getId(), userId)) {
            return new InvitePreviewResponse(group.getId(), group.getTitle(), group.getDescription(),
                group.getAvatarUrl(), memberCount, "ALREADY_MEMBER");
        }
        if (groupBlockRepository.existsByGroupIdAndUserId(group.getId(), userId)) {
            return new InvitePreviewResponse(group.getId(), group.getTitle(), group.getDescription(),
                group.getAvatarUrl(), memberCount, "BLOCKED");
        }
        return new InvitePreviewResponse(group.getId(), group.getTitle(), group.getDescription(),
            group.getAvatarUrl(), memberCount, "JOINABLE");
    }

    /**
     * Consume one use of {@code token} and add the caller to the group.
     *
     * @return the group's id, so the caller can fetch the conversation next
     */
    @Transactional
    public UUID join(UUID userId, String token) {
        if (!isWellFormed(token)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "That invite link is not valid");
        }
        UUID groupId = linkRepository.findGroupIdByToken(token.toLowerCase())
            .orElseThrow(() -> new ChatException(ChatException.INVALID_COMMAND, "That invite link is not valid"));

        // Group row first (the same lock every other membership path takes),
        // then the link row: two visitors sharing one link serialize here
        // instead of both passing the max-uses check. The link is deliberately
        // read for the first time *after* the locks are held.
        ConversationEntity group = conversationRepository.findByIdForUpdate(groupId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));
        if (group.getType() != ConversationType.GROUP) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Can only join group conversations");
        }

        if (groupBlockRepository.existsByGroupIdAndUserId(group.getId(), userId)) {
            throw new ChatException(ChatException.USER_BLOCKED, "You are blocked from this group");
        }

        // Being a member already answers the request without spending a use:
        // the link was paid for when this person first walked through it, and
        // a link that has since expired must not lock out someone who is in.
        if (memberRepository.existsByConversationIdAndUserId(group.getId(), userId)) {
            return group.getId();
        }

        GroupInviteLinkEntity link = linkRepository.findByTokenForUpdate(token.toLowerCase())
            .orElseThrow(() -> new ChatException(ChatException.INVALID_COMMAND, "That invite link is not valid"));
        if (!link.isActiveAt(Instant.now())) {
            throw new ChatException(ChatException.INVALID_COMMAND, "That invite link is no longer valid");
        }

        memberRepository.save(new ConversationMemberEntity(group.getId(), userId, MemberRole.MEMBER));
        link.setUsedCount(link.getUsedCount() + 1);
        linkRepository.save(link);
        acceptPendingRequest(group.getId(), userId);

        return group.getId();
    }

    /**
     * A link join resolves the same pending join request an admin would have
     * had to approve, so the approvals list does not keep showing a person who
     * is already in the group.
     */
    private void acceptPendingRequest(UUID groupId, UUID userId) {
        joinRequestRepository.findByGroupIdAndUserId(groupId, userId)
            .filter(request -> request.getStatus() == GroupJoinRequestStatus.PENDING)
            .ifPresent(request -> {
                request.setStatus(GroupJoinRequestStatus.ACCEPTED);
                joinRequestRepository.save(request);
            });
    }

    private static boolean isWellFormed(String token) {
        return token != null && TOKEN_FORMAT.matcher(token.toLowerCase()).matches();
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        StringBuilder token = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            token.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return token.toString();
    }

    private ConversationEntity requireGroup(UUID groupId) {
        ConversationEntity group = conversationRepository.findById(groupId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));
        if (group.getType() != ConversationType.GROUP) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Invite links belong to group conversations");
        }
        return group;
    }

    private void requireAdmin(UUID groupId, UUID userId) {
        ConversationMemberEntity member = memberRepository.findByConversationIdAndUserId(groupId, userId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));
        if (member.getRole() != MemberRole.OWNER && member.getRole() != MemberRole.MODERATOR) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can manage invite links");
        }
    }

    private GroupInviteLinkResponse map(GroupInviteLinkEntity link, ConversationEntity group) {
        return new GroupInviteLinkResponse(
            link.getId(),
            link.getGroupId(),
            group.getTitle(),
            link.getToken(),
            "/app#join/" + link.getToken(),
            link.getCreatedAt(),
            link.getExpiresAt(),
            link.getMaxUses(),
            link.getUsedCount(),
            link.isRevoked(),
            link.isActiveAt(Instant.now())
        );
    }
}
