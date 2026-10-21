package com.example.chat.application;

import com.example.chat.domain.entity.FriendRequestEntity;
import com.example.chat.domain.entity.FriendRequestStatus;
import com.example.chat.domain.entity.FriendshipEntity;
import com.example.chat.domain.entity.UserEntity;
import com.example.chat.dto.friend.FriendRequestResponse;
import com.example.chat.dto.friend.FriendResponse;
import com.example.chat.dto.friend.FriendshipStatusResponse;
import com.example.chat.dto.friend.SendFriendRequest;
import com.example.chat.exception.ChatException;
import com.example.chat.infrastructure.repository.FriendRequestRepository;
import com.example.chat.infrastructure.repository.FriendshipRepository;
import com.example.chat.infrastructure.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class FriendService {

    private final FriendshipRepository friendshipRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final UserRepository userRepository;
    private final PresenceService presenceService;

    public FriendService(FriendshipRepository friendshipRepository,
                         FriendRequestRepository friendRequestRepository,
                         UserRepository userRepository,
                         PresenceService presenceService) {
        this.friendshipRepository = friendshipRepository;
        this.friendRequestRepository = friendRequestRepository;
        this.userRepository = userRepository;
        this.presenceService = presenceService;
    }

    @Transactional
    public FriendRequestResponse sendFriendRequest(UUID senderId, SendFriendRequest request) {
        UserEntity receiver;
        if (request.userId() != null) {
            receiver = userRepository.findById(request.userId())
                .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));
        } else if (request.username() != null && !request.username().trim().isEmpty()) {
            receiver = userRepository.findByUsernameIgnoreCase(request.username().trim())
                .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "User not found"));
        } else {
            throw new ChatException(ChatException.INVALID_COMMAND, "Username or userId is required");
        }

        if (receiver.isDeactivated()) {
            throw new ChatException(ChatException.USER_NOT_FOUND, "User account is deactivated");
        }

        if (senderId.equals(receiver.getId())) {
            throw new ChatException(ChatException.CANNOT_FRIEND_SELF, "You cannot send a friend request to yourself");
        }

        if (friendshipRepository.existsByUserIdAndFriendId(senderId, receiver.getId())) {
            throw new ChatException(ChatException.ALREADY_FRIENDS, "You are already friends with this user");
        }

        // Check if there is already a pending request from receiver to sender (cross-request)
        Optional<FriendRequestEntity> reversePending = friendRequestRepository.findPendingBetween(
            receiver.getId(), senderId, FriendRequestStatus.PENDING
        );
        if (reversePending.isPresent()) {
            FriendRequestEntity existing = reversePending.get();
            if (existing.getSenderId().equals(receiver.getId())) {
                // Sender is now accepting the other user's pending request!
                return acceptFriendRequest(senderId, existing.getId());
            } else {
                throw new ChatException(ChatException.REQUEST_ALREADY_EXISTS, "Friend request already sent");
            }
        }

        String message = request.message() == null || request.message().isBlank()
            ? null
            : request.message().trim();
        FriendRequestEntity friendRequest = new FriendRequestEntity(senderId, receiver.getId(), message);
        friendRequest = friendRequestRepository.save(friendRequest);

        UserEntity sender = userRepository.findById(senderId)
            .orElseThrow(() -> new ChatException(ChatException.USER_NOT_FOUND, "Sender user not found"));

        return mapToRequestResponse(friendRequest, sender, receiver);
    }

    @Transactional
    public FriendRequestResponse acceptFriendRequest(UUID currentUserId, UUID requestId) {
        FriendRequestEntity request = friendRequestRepository.findById(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Friend request not found"));

        if (!request.getReceiverId().equals(currentUserId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You are not authorized to accept this friend request");
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Friend request is not pending");
        }

        request.setStatus(FriendRequestStatus.ACCEPTED);
        request = friendRequestRepository.save(request);

        // Create mutual friendship
        if (!friendshipRepository.existsByUserIdAndFriendId(request.getSenderId(), request.getReceiverId())) {
            friendshipRepository.save(new FriendshipEntity(request.getSenderId(), request.getReceiverId()));
        }
        if (!friendshipRepository.existsByUserIdAndFriendId(request.getReceiverId(), request.getSenderId())) {
            friendshipRepository.save(new FriendshipEntity(request.getReceiverId(), request.getSenderId()));
        }

        UserEntity sender = userRepository.findById(request.getSenderId()).orElse(null);
        UserEntity receiver = userRepository.findById(request.getReceiverId()).orElse(null);

        return mapToRequestResponse(request, sender, receiver);
    }

    @Transactional
    public FriendRequestResponse rejectFriendRequest(UUID currentUserId, UUID requestId) {
        FriendRequestEntity request = friendRequestRepository.findById(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Friend request not found"));

        if (!request.getReceiverId().equals(currentUserId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You are not authorized to reject this friend request");
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Friend request is not pending");
        }

        request.setStatus(FriendRequestStatus.REJECTED);
        request = friendRequestRepository.save(request);

        UserEntity sender = userRepository.findById(request.getSenderId()).orElse(null);
        UserEntity receiver = userRepository.findById(request.getReceiverId()).orElse(null);

        return mapToRequestResponse(request, sender, receiver);
    }

    @Transactional
    public FriendRequestResponse cancelFriendRequest(UUID currentUserId, UUID requestId) {
        FriendRequestEntity request = friendRequestRepository.findById(requestId)
            .orElseThrow(() -> new ChatException(ChatException.REQUEST_NOT_FOUND, "Friend request not found"));

        if (!request.getSenderId().equals(currentUserId)) {
            throw new ChatException(ChatException.FORBIDDEN, "You are not authorized to cancel this friend request");
        }

        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Friend request is not pending");
        }

        request.setStatus(FriendRequestStatus.CANCELLED);
        request = friendRequestRepository.save(request);

        UserEntity sender = userRepository.findById(request.getSenderId()).orElse(null);
        UserEntity receiver = userRepository.findById(request.getReceiverId()).orElse(null);

        return mapToRequestResponse(request, sender, receiver);
    }

    @Transactional(readOnly = true)
    public List<FriendRequestResponse> getIncomingRequests(UUID currentUserId) {
        List<FriendRequestEntity> requests = friendRequestRepository.findByReceiverIdAndStatusOrderByCreatedAtDesc(
            currentUserId, FriendRequestStatus.PENDING
        );
        UserEntity receiver = userRepository.findById(currentUserId).orElse(null);

        return requests.stream().map(req -> {
            UserEntity sender = userRepository.findById(req.getSenderId()).orElse(null);
            return mapToRequestResponse(req, sender, receiver);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<FriendRequestResponse> getOutgoingRequests(UUID currentUserId) {
        List<FriendRequestEntity> requests = friendRequestRepository.findBySenderIdAndStatusOrderByCreatedAtDesc(
            currentUserId, FriendRequestStatus.PENDING
        );
        UserEntity sender = userRepository.findById(currentUserId).orElse(null);

        return requests.stream().map(req -> {
            UserEntity receiver = userRepository.findById(req.getReceiverId()).orElse(null);
            return mapToRequestResponse(req, sender, receiver);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> getFriends(UUID currentUserId) {
        List<FriendshipEntity> list = friendshipRepository.findByUserId(currentUserId);
        List<FriendResponse> friends = new ArrayList<>();

        for (FriendshipEntity f : list) {
            userRepository.findById(f.getFriendId()).ifPresent(user -> {
                if (!user.isDeactivated()) {
                    boolean isOnline = presenceService.isOnline(user.getUsername());
                    friends.add(new FriendResponse(
                        user.getId(),
                        user.getUsername(),
                        user.getDisplayName(),
                        user.getAvatarUrl(),
                        user.getBio(),
                        isOnline,
                        f.getCreatedAt()
                    ));
                }
            });
        }
        return friends;
    }

    @Transactional
    public void removeFriend(UUID currentUserId, UUID friendId) {
        if (!friendshipRepository.existsByUserIdAndFriendId(currentUserId, friendId)) {
            throw new ChatException(ChatException.INVALID_COMMAND, "Friendship not found");
        }
        friendshipRepository.deleteByUserIdAndFriendId(currentUserId, friendId);
        friendshipRepository.deleteByUserIdAndFriendId(friendId, currentUserId);
    }

    @Transactional(readOnly = true)
    public FriendshipStatusResponse getFriendshipStatus(UUID currentUserId, UUID targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            return new FriendshipStatusResponse("SELF", null);
        }
        if (friendshipRepository.existsByUserIdAndFriendId(currentUserId, targetUserId)) {
            return new FriendshipStatusResponse("FRIENDS", null);
        }
        Optional<FriendRequestEntity> pending = friendRequestRepository.findPendingBetween(
            currentUserId, targetUserId, FriendRequestStatus.PENDING
        );
        if (pending.isPresent()) {
            FriendRequestEntity r = pending.get();
            if (r.getSenderId().equals(currentUserId)) {
                return new FriendshipStatusResponse("PENDING_SENT", r.getId());
            } else {
                return new FriendshipStatusResponse("PENDING_RECEIVED", r.getId());
            }
        }
        return new FriendshipStatusResponse("NONE", null);
    }

    private FriendRequestResponse mapToRequestResponse(FriendRequestEntity req, UserEntity sender, UserEntity receiver) {
        return new FriendRequestResponse(
            req.getId(),
            req.getSenderId(),
            sender != null ? sender.getUsername() : "unknown",
            sender != null ? sender.getDisplayName() : "Unknown",
            sender != null ? sender.getAvatarUrl() : null,
            req.getReceiverId(),
            receiver != null ? receiver.getUsername() : "unknown",
            receiver != null ? receiver.getDisplayName() : "Unknown",
            receiver != null ? receiver.getAvatarUrl() : null,
            req.getStatus(),
            req.getMessage(),
            req.getCreatedAt()
        );
    }
}
