package com.example.chat.application;

import com.example.chat.domain.entity.*;
import com.example.chat.dto.conversation.*;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final PresenceService presenceService;

    public ConversationService(ConversationRepository conversationRepository,
                               ConversationMemberRepository memberRepository,
                               UserRepository userRepository,
                               MessageRepository messageRepository,
                               PresenceService presenceService) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.presenceService = presenceService;
    }

    @Transactional
    public ConversationSummaryResponse getOrCreateDirect(UUID currentUserId, UUID targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot start a conversation with yourself");
        }

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

    @Transactional
    public ConversationSummaryResponse createGroup(UUID currentUserId, CreateGroupRequest request) {
        String title = request.title().trim();
        ConversationEntity group = new ConversationEntity(ConversationType.GROUP, title, currentUserId);
        if (request.description() != null) group.setDescription(request.description().trim());
        if (request.avatarUrl() != null) group.setAvatarUrl(request.avatarUrl().trim());
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
        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        if (conv.getType() == ConversationType.DIRECT) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot add members to a direct conversation");
        }

        if (memberRepository.existsByConversationIdAndUserId(conversationId, request.userId())) {
            return; // already member
        }

        if (!userRepository.existsById(request.userId())) {
            throw new ChatException(ChatException.USER_NOT_FOUND, "User not found");
        }

        ConversationMemberEntity newMember = new ConversationMemberEntity(conversationId, request.userId(), MemberRole.MEMBER);
        memberRepository.save(newMember);
    }

    @Transactional
    public void removeMember(UUID conversationId, UUID currentUserId, UUID memberIdToRemove) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        if (conv.getType() == ConversationType.DIRECT) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot remove members from a direct conversation");
        }

        // Only owner can remove others; any member can remove themselves (leave)
        boolean isSelf = currentUserId.equals(memberIdToRemove);
        boolean isOwner = currentMember.getRole() == MemberRole.OWNER;

        if (!isSelf && !isOwner) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group owner can remove members");
        }

        memberRepository.deleteByConversationIdAndUserId(conversationId, memberIdToRemove);
    }

    @Transactional
    public ConversationSummaryResponse updateGroup(UUID conversationId, UUID currentUserId, UpdateGroupRequest request) {
        ConversationMemberEntity currentMember = requireMembership(conversationId, currentUserId);
        if (currentMember.getRole() != MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group owner can update group details");
        }

        ConversationEntity conv = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Conversation not found"));

        conv.setTitle(request.title().trim());
        if (request.description() != null) conv.setDescription(request.description().trim());
        if (request.avatarUrl() != null) conv.setAvatarUrl(request.avatarUrl().trim());
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

    @Transactional
    public void markAsRead(UUID conversationId, UUID currentUserId) {
        ConversationMemberEntity member = requireMembership(conversationId, currentUserId);
        member.setLastReadAt(Instant.now());
        memberRepository.save(member);
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
            otherUsername,
            otherUserId
        );
    }
}
