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

    public GroupJoinRequestService(GroupJoinRequestRepository groupJoinRequestRepository,
                                     ConversationRepository conversationRepository,
                                     ConversationMemberRepository memberRepository,
                                     UserRepository userRepository) {
        this.groupJoinRequestRepository = groupJoinRequestRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public GroupJoinRequestResponse requestToJoin(UUID userId, UUID groupId) {
        ConversationEntity group = conversationRepository.findById(groupId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));

        if (group.getType() != ConversationType.GROUP) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Can only join group conversations");
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

        return mapToJoinRequestResponse(request, group, user);
    }

    @Transactional
    public void cancelJoinRequest(UUID userId, UUID requestId) {
        GroupJoinRequestEntity request = groupJoinRequestRepository.findById(requestId)
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
        GroupJoinRequestEntity request = groupJoinRequestRepository.findById(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Join request not found"));

        ConversationMemberEntity adminMember = memberRepository.findByConversationIdAndUserId(request.getGroupId(), adminUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (adminMember.getRole() != MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group owners can accept join requests");
        }

        if (request.getStatus() != GroupJoinRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Request is not pending");
        }

        request.setStatus(GroupJoinRequestStatus.ACCEPTED);
        request = groupJoinRequestRepository.save(request);

        ConversationMemberEntity newMember = new ConversationMemberEntity(request.getGroupId(), request.getUserId(), MemberRole.MEMBER);
        memberRepository.save(newMember);

        ConversationEntity group = conversationRepository.findById(request.getGroupId()).orElse(null);
        UserEntity user = userRepository.findById(request.getUserId()).orElse(null);

        return mapToJoinRequestResponse(request, group, user);
    }

    @Transactional
    public GroupJoinRequestResponse rejectJoinRequest(UUID adminUserId, UUID requestId) {
        GroupJoinRequestEntity request = groupJoinRequestRepository.findById(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Join request not found"));

        ConversationMemberEntity adminMember = memberRepository.findByConversationIdAndUserId(request.getGroupId(), adminUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (adminMember.getRole() != MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group owners can reject join requests");
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

        if (adminMember.getRole() != MemberRole.OWNER) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group owners can view join requests");
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

            int memberCount = memberRepository.findByConversationId(group.getId()).size();
            String membershipStatus = "NONE";

            if (memberRepository.existsByConversationIdAndUserId(group.getId(), userId)) {
                membershipStatus = "MEMBER";
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
                membershipStatus,
                group.getCreatedAt()
            ));
        }

        return responses;
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
