package com.homefix.auth.invitation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link StaffInvitation}s. */
public interface StaffInvitationRepository extends JpaRepository<StaffInvitation, UUID> {

    Optional<StaffInvitation> findByTokenHash(String tokenHash);

    /** Open invitations (not accepted, not revoked) for an address, expired ones included. */
    @Query("select i from StaffInvitation i where i.email = :email and i.acceptedAt is null and i.revokedAt is null")
    List<StaffInvitation> findOpenByEmail(@Param("email") String email);

    /** Invitations that still work, newest first: the Admin Portal's Invitations tab. */
    @Query("""
            select i from StaffInvitation i
            where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now
            order by i.createdAt desc
            """)
    List<StaffInvitation> findUsable(@Param("now") Instant now);

    /**
     * Marks the invitation accepted if it is still open, as one statement: of two concurrent
     * acceptances only one changes a row.
     *
     * @return 1 if this call accepted it, 0 if it was no longer open
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update StaffInvitation i set i.acceptedAt = :at, i.acceptedUserId = :userId
            where i.id = :id and i.acceptedAt is null and i.revokedAt is null
            """)
    int markAccepted(@Param("id") UUID id, @Param("userId") UUID userId, @Param("at") Instant at);
}
