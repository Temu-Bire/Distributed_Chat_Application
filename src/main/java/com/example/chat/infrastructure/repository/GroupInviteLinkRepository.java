package com.example.chat.infrastructure.repository;

import com.example.chat.domain.entity.GroupInviteLinkEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupInviteLinkRepository extends JpaRepository<GroupInviteLinkEntity, UUID> {

    Optional<GroupInviteLinkEntity> findByToken(String token);

    /** Row lock so two joins through the same link cannot both consume the last use. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM GroupInviteLinkEntity l WHERE l.token = :token")
    Optional<GroupInviteLinkEntity> findByTokenForUpdate(@Param("token") String token);

    List<GroupInviteLinkEntity> findByGroupIdAndRevokedFalseOrderByCreatedAtDesc(UUID groupId);
}
