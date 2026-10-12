package com.homefix.auth.invitation;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.homefix.auth.domain.Role;

/**
 * An invitation for an email address to join the Admin Portal with one staff role (email-auth
 * Requirement 6). Only the SHA-256 of the emailed token is stored. Open means neither accepted nor
 * revoked; an open invitation past {@link #getExpiresAt()} is expired.
 */
@Entity
@Table(name = "staff_invitation")
public class StaffInvitation {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Lower-case address the invitation was sent to. */
    @Column(name = "email", nullable = false, length = 254, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32, updatable = false)
    private Role role;

    /** Hex SHA-256 of the token in the link. */
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected StaffInvitation() {
        // JPA
    }

    public StaffInvitation(String email, Role role, String tokenHash, UUID invitedBy, Instant createdAt,
                           Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Neither accepted nor revoked (it may still have expired). */
    public boolean isOpen() {
        return acceptedAt == null && revokedAt == null;
    }

    /** Open and not yet expired: the link still works. */
    public boolean isUsable(Instant now) {
        return isOpen() && now.isBefore(expiresAt);
    }

    public void revoke(Instant at) {
        if (isOpen()) {
            this.revokedAt = at;
        }
    }

    public void accept(UUID userId, Instant at) {
        this.acceptedAt = at;
        this.acceptedUserId = userId;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public UUID getAcceptedUserId() {
        return acceptedUserId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
