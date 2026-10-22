package com.example.chat.application;

import com.example.chat.domain.entity.*;
import com.example.chat.dto.group.GroupDiscoveryResponse;
import com.example.chat.dto.group.GroupJoinRequestResponse;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GroupJoinRequestService {

    private final GroupJoinRequestRepository groupJoinRequestRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final GroupBlockRepository groupBlockRepository;
    private final NotificationService notificationService;

    public GroupJoinRequestService(GroupJoinRequestRepository groupJoinRequestRepository,
                                     ConversationRepository conversationRepository,
                                     ConversationMemberRepository memberRepository,
                                     UserRepository userRepository,
                                     GroupBlockRepository groupBlockRepository,
                                     NotificationService notificationService) {
        this.groupJoinRequestRepository = groupJoinRequestRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.groupBlockRepository = groupBlockRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public GroupJoinRequestResponse requestToJoin(UUID userId, UUID groupId) {
        // Group row lock: keeps the member/request checks below atomic against
        // concurrent joins and admin adds on the same group.
        ConversationEntity group = conversationRepository.findByIdForUpdate(groupId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));

        if (group.getType() != ConversationType.GROUP) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Can only join group conversations");
        }

        if (groupBlockRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ChatException(ChatException.USER_BLOCKED, "You are blocked from this group");
        }

        if (memberRepository.existsByConversationIdAndUserId(groupId, userId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "You are already a member of this group");
        }

        if (groupJoinRequestRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ChatException(ChatException.REQUEST_ALREADY_EXISTS, "You already have a pending join request for this group");
        }

        GroupJoinRequestEntity request = new GroupJoinRequestEntity(groupId, userId);
        request = groupJoinRequestRepository.save(request);

        UserEntity user = userRepository.findById(userId).orElse(null);

        if (user != null) {
            notificationService.notifyJoinRequest(user.getId(), user.getUsername(), group.getId(), group.getTitle());
        }

        return mapToJoinRequestResponse(request, group, user);
    }

    @Transactional
    public void cancelJoinRequest(UUID userId, UUID requestId) {
        GroupJoinRequestEntity request = groupJoinRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Join request not found"));

        if (!request.getUserId().equals(userId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You can only cancel your own join requests");
        }

        if (request.getStatus() != GroupJoinRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot cancel a non-pending request");
        }

        request.setStatus(GroupJoinRequestStatus.CANCELLED);
        groupJoinRequestRepository.save(request);
    }

    @Transactional
    public GroupJoinRequestResponse acceptJoinRequest(UUID adminUserId, UUID requestId) {
        // Request row first: a second admin accepting the same request blocks
        // here and then sees ACCEPTED, instead of double-inserting the member
        // and colliding with uq_conv_member at commit.
        GroupJoinRequestEntity request = groupJoinRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Join request not found"));

        ConversationMemberEntity adminMember = memberRepository.findByConversationIdAndUserId(request.getGroupId(), adminUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (!isAdmin(adminMember)) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can accept join requests");
        }

        if (request.getStatus() != GroupJoinRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Request is not pending");
        }

        // Group row second (fixed lock order request -> group, and no path
        // takes them the other way round): the membership insert below then
        // serializes against joinGroup and addMember.
        ConversationEntity group = conversationRepository.findByIdForUpdate(request.getGroupId())
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));

        if (groupBlockRepository.existsByGroupIdAndUserId(group.getId(), request.getUserId())) {
            throw new ChatException(ChatException.USER_BLOCKED, "This user is blocked from the group");
        }

        request.setStatus(GroupJoinRequestStatus.ACCEPTED);
        request = groupJoinRequestRepository.save(request);

        if (!memberRepository.existsByConversationIdAndUserId(group.getId(), request.getUserId())) {
            memberRepository.save(new ConversationMemberEntity(group.getId(), request.getUserId(), MemberRole.MEMBER));
        }

        UserEntity user = userRepository.findById(request.getUserId()).orElse(null);

        if (user != null) {
            String actorUsername = userRepository.findById(adminUserId)
                .map(admin -> admin.getUsername())
                .orElse("a group admin");
            notificationService.notifyJoinAccepted(user.getId(), actorUsername, group.getId(), group.getTitle());
        }

        return mapToJoinRequestResponse(request, group, user);
    }

    @Transactional
    public GroupJoinRequestResponse rejectJoinRequest(UUID adminUserId, UUID requestId) {
        GroupJoinRequestEntity request = groupJoinRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Join request not found"));

        ConversationMemberEntity adminMember = memberRepository.findByConversationIdAndUserId(request.getGroupId(), adminUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (!isAdmin(adminMember)) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can reject join requests");
        }

        if (request.getStatus() != GroupJoinRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Request is not pending");
        }

        request.setStatus(GroupJoinRequestStatus.REJECTED);
        request = groupJoinRequestRepository.save(request);

        ConversationEntity group = conversationRepository.findById(request.getGroupId()).orElse(null);
        UserEntity user = userRepository.findById(request.getUserId()).orElse(null);

        return mapToJoinRequestResponse(request, group, user);
    }

    @Transactional(readOnly = true)
    public List<GroupJoinRequestResponse> getPendingRequestsForGroup(UUID groupId, UUID adminUserId) {
        ConversationMemberEntity adminMember = memberRepository.findByConversationIdAndUserId(groupId, adminUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (!isAdmin(adminMember)) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can view join requests");
        }

        List<GroupJoinRequestEntity> requests = groupJoinRequestRepository.findByGroupIdAndStatusOrderByCreatedAtDesc(
            groupId, GroupJoinRequestStatus.PENDING
        );

        ConversationEntity group = conversationRepository.findById(groupId).orElse(null);
        List<GroupJoinRequestResponse> responses = new ArrayList<>();

        for (GroupJoinRequestEntity req : requests) {
            UserEntity user = userRepository.findById(req.getUserId()).orElse(null);
            responses.add(mapToJoinRequestResponse(req, group, user));
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<GroupJoinRequestResponse> getUserJoinRequests(UUID userId) {
        List<GroupJoinRequestEntity> requests = groupJoinRequestRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
            userId, GroupJoinRequestStatus.PENDING
        );

        List<GroupJoinRequestResponse> responses = new ArrayList<>();

        for (GroupJoinRequestEntity req : requests) {
            ConversationEntity group = conversationRepository.findById(req.getGroupId()).orElse(null);
            UserEntity user = userRepository.findById(userId).orElse(null);
            responses.add(mapToJoinRequestResponse(req, group, user));
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<GroupDiscoveryResponse> discoverGroups(UUID userId, String query, Pageable pageable) {
        Page<ConversationEntity> groups;
        if (query != null && !query.trim().isEmpty()) {
            groups = conversationRepository.findGroupsByTitleContaining(query.trim(), pageable);
        } else {
            groups = conversationRepository.findGroups(pageable);
        }

        List<GroupDiscoveryResponse> responses = new ArrayList<>();

        for (ConversationEntity group : groups) {
            if (group.getType() != ConversationType.GROUP) continue;

            int memberCount = (int) memberRepository.countByConversationId(group.getId());
            String membershipStatus = "NONE";

            if (memberRepository.existsByConversationIdAndUserId(group.getId(), userId)) {
                membershipStatus = "MEMBER";
            } else if (groupBlockRepository.existsByGroupIdAndUserId(group.getId(), userId)) {
                membershipStatus = "BLOCKED";
            } else if (groupJoinRequestRepository.existsByGroupIdAndUserId(group.getId(), userId)) {
                membershipStatus = "PENDING";
            }

            responses.add(new GroupDiscoveryResponse(
                group.getId(),
                group.getTitle(),
                group.getDescription(),
                group.getAvatarUrl(),
                memberCount,
                group.getType().name(),
                group.isPrivate(),
                membershipStatus,
                group.getCreatedAt()
            ));
        }

        return responses;
    }

    private static boolean isAdmin(ConversationMemberEntity member) {
        return member.getRole() == MemberRole.OWNER || member.getRole() == MemberRole.MODERATOR;
    }

    private GroupJoinRequestResponse mapToJoinRequestResponse(GroupJoinRequestEntity req, ConversationEntity group, UserEntity user) {
        return new GroupJoinRequestResponse(
            req.getId(),
            req.getGroupId(),
            group != null ? group.getTitle() : "Unknown Group",
            group != null ? group.getAvatarUrl() : null,
            req.getUserId(),
            user != null ? user.getUsername() : "unknown",
            user != null ? user.getDisplayName() : "Unknown",
            user != null ? user.getAvatarUrl() : null,
            req.getStatus().name(),
            req.getCreatedAt()
        );
    }
}
