package com.example.chat.application;

import com.example.chat.domain.entity.*;
import com.example.chat.dto.group.GroupInvitationResponse;
import com.example.chat.dto.group.InviteToGroupRequest;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GroupInvitationService {

    private final GroupInvitationRepository groupInvitationRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final GroupBlockRepository groupBlockRepository;
    private final NotificationService notificationService;

    public GroupInvitationService(GroupInvitationRepository groupInvitationRepository,
                                    ConversationRepository conversationRepository,
                                    ConversationMemberRepository memberRepository,
                                    UserRepository userRepository,
                                    GroupBlockRepository groupBlockRepository,
                                    NotificationService notificationService) {
        this.groupInvitationRepository = groupInvitationRepository;
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.groupBlockRepository = groupBlockRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public List<GroupInvitationResponse> inviteUsers(UUID inviterId, UUID groupId, InviteToGroupRequest request) {
        ConversationEntity group = conversationRepository.findById(groupId)
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));

        if (group.getType() != ConversationType.GROUP) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Can only invite to group conversations");
        }

        ConversationMemberEntity inviterMember = memberRepository.findByConversationIdAndUserId(groupId, inviterId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (inviterMember.getRole() != MemberRole.OWNER && inviterMember.getRole() != MemberRole.MODERATOR) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can invite members");
        }

        List<GroupInvitationResponse> responses = new ArrayList<>();

        for (UUID userId : request.userIds()) {
            if (userId.equals(inviterId)) {
                continue;
            }

            if (!userRepository.existsById(userId)) {
                continue;
            }

            if (memberRepository.existsByConversationIdAndUserId(groupId, userId)) {
                continue;
            }

            if (groupBlockRepository.existsByGroupIdAndUserId(groupId, userId)) {
                continue; // blocked users cannot be invited
            }

            if (groupInvitationRepository.existsByGroupIdAndInvitedUserId(groupId, userId)) {
                continue;
            }

            GroupInvitationEntity invitation = new GroupInvitationEntity(groupId, inviterId, userId);
            invitation = groupInvitationRepository.save(invitation);

            UserEntity inviter = userRepository.findById(inviterId).orElse(null);
            UserEntity invitedUser = userRepository.findById(userId).orElse(null);

            if (inviter != null) {
                notificationService.notifyGroupInvitation(inviter.getId(), inviter.getUsername(),
                    userId, group.getId(), group.getTitle());
            }

            responses.add(mapToInvitationResponse(invitation, group, inviter, invitedUser));
        }

        return responses;
    }

    @Transactional
    public GroupInvitationResponse acceptInvitation(UUID userId, UUID invitationId) {
        GroupInvitationEntity invitation = groupInvitationRepository.findByIdForUpdate(invitationId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Invitation not found"));

        if (!invitation.getInvitedUserId().equals(userId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You can only accept invitations sent to you");
        }

        if (invitation.getStatus() != GroupInvitationStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Invitation is not pending");
        }

        // Fixed lock order invitation -> group, matching acceptJoinRequest;
        // the group lock serializes this membership insert with joinGroup,
        // addMember and request acceptance, so uq_conv_member cannot be hit
        // by two racing transactions.
        ConversationEntity group = conversationRepository.findByIdForUpdate(invitation.getGroupId())
            .orElseThrow(() -> new ChatException(ChatException.CONVERSATION_NOT_FOUND, "Group not found"));

        if (groupBlockRepository.existsByGroupIdAndUserId(group.getId(), userId)) {
            throw new ChatException(ChatException.USER_BLOCKED, "You are blocked from this group");
        }

        invitation.setStatus(GroupInvitationStatus.ACCEPTED);
        invitation = groupInvitationRepository.save(invitation);

        if (!memberRepository.existsByConversationIdAndUserId(group.getId(), userId)) {
            ConversationMemberEntity newMember = new ConversationMemberEntity(group.getId(), userId, MemberRole.MEMBER);
            memberRepository.save(newMember);
        }

        UserEntity inviter = userRepository.findById(invitation.getInviterId()).orElse(null);
        UserEntity invitedUser = userRepository.findById(userId).orElse(null);

        return mapToInvitationResponse(invitation, group, inviter, invitedUser);
    }

    @Transactional
    public GroupInvitationResponse rejectInvitation(UUID userId, UUID invitationId) {
        GroupInvitationEntity invitation = groupInvitationRepository.findByIdForUpdate(invitationId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Invitation not found"));

        if (!invitation.getInvitedUserId().equals(userId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You can only reject invitations sent to you");
        }

        if (invitation.getStatus() != GroupInvitationStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Invitation is not pending");
        }

        invitation.setStatus(GroupInvitationStatus.REJECTED);
        invitation = groupInvitationRepository.save(invitation);

        ConversationEntity group = conversationRepository.findById(invitation.getGroupId()).orElse(null);
        UserEntity inviter = userRepository.findById(invitation.getInviterId()).orElse(null);
        UserEntity invitedUser = userRepository.findById(userId).orElse(null);

        return mapToInvitationResponse(invitation, group, inviter, invitedUser);
    }

    @Transactional
    public void cancelInvitation(UUID inviterId, UUID invitationId) {
        GroupInvitationEntity invitation = groupInvitationRepository.findByIdForUpdate(invitationId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Invitation not found"));

        if (!invitation.getInviterId().equals(inviterId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You can only cancel invitations you sent");
        }

        if (invitation.getStatus() != GroupInvitationStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Cannot cancel a non-pending invitation");
        }

        invitation.setStatus(GroupInvitationStatus.CANCELLED);
        groupInvitationRepository.save(invitation);
    }

    @Transactional(readOnly = true)
    public List<GroupInvitationResponse> getUserInvitations(UUID userId) {
        List<GroupInvitationEntity> invitations = groupInvitationRepository.findByInvitedUserIdAndStatusOrderByCreatedAtDesc(
            userId, GroupInvitationStatus.PENDING
        );

        List<GroupInvitationResponse> responses = new ArrayList<>();

        for (GroupInvitationEntity inv : invitations) {
            ConversationEntity group = conversationRepository.findById(inv.getGroupId()).orElse(null);
            UserEntity inviter = userRepository.findById(inv.getInviterId()).orElse(null);
            UserEntity invitedUser = userRepository.findById(userId).orElse(null);
            responses.add(mapToInvitationResponse(inv, group, inviter, invitedUser));
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<GroupInvitationResponse> getGroupInvitations(UUID groupId, UUID adminUserId) {
        ConversationMemberEntity adminMember = memberRepository.findByConversationIdAndUserId(groupId, adminUserId)
            .orElseThrow(() -> new ChatException(ChatException.NOT_MEMBER, "You are not a member of this group"));

        if (adminMember.getRole() != MemberRole.OWNER && adminMember.getRole() != MemberRole.MODERATOR) {
            throw new ChatException(ChatException.FORBIDDEN, "Only group admins can view invitations");
        }

        List<GroupInvitationEntity> invitations = groupInvitationRepository.findByGroupIdAndStatusOrderByCreatedAtDesc(
            groupId, GroupInvitationStatus.PENDING
        );

        ConversationEntity group = conversationRepository.findById(groupId).orElse(null);
        List<GroupInvitationResponse> responses = new ArrayList<>();

        for (GroupInvitationEntity inv : invitations) {
            UserEntity inviter = userRepository.findById(inv.getInviterId()).orElse(null);
            UserEntity invitedUser = userRepository.findById(inv.getInvitedUserId()).orElse(null);
            responses.add(mapToInvitationResponse(inv, group, inviter, invitedUser));
        }

        return responses;
    }

    private GroupInvitationResponse mapToInvitationResponse(GroupInvitationEntity inv, ConversationEntity group, UserEntity inviter, UserEntity invitedUser) {
        return new GroupInvitationResponse(
            inv.getId(),
            inv.getGroupId(),
            group != null ? group.getTitle() : "Unknown Group",
            group != null ? group.getAvatarUrl() : null,
            inv.getInviterId(),
            inviter != null ? inviter.getUsername() : "unknown",
            inviter != null ? inviter.getDisplayName() : "Unknown",
            inviter != null ? inviter.getAvatarUrl() : null,
            inv.getInvitedUserId(),
            inv.getStatus().name(),
            inv.getCreatedAt()
        );
    }
}
