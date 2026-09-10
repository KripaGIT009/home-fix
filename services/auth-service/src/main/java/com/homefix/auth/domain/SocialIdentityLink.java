package com.homefix.auth.domain;

import java.time.Instant;
import java.util.UUID;

import com.homefix.auth.social.SocialProvider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Links a social provider identity ({@code provider} + provider's stable {@code sub}) to a
 * platform {@link UserAccount}, so repeat social logins retrieve the same account
 * (Requirement 1.5).
 *
 * <p>The {@code (provider, provider_subject)} pair is unique: one provider identity maps to
 * exactly one platform account.
 */
@Entity
@Table(name = "social_identity_link",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_social_identity_provider_subject",
                columnNames = {"provider", "provider_subject"}))
public class SocialIdentityLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private SocialProvider provider;

    @Column(name = "provider_subject", nullable = false, length = 255)
    private String providerSubject;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SocialIdentityLink() {
        // JPA
    }

    private SocialIdentityLink(UUID id, SocialProvider provider, String providerSubject,
                               UUID userId, Instant createdAt) {
        this.id = id;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.userId = userId;
        this.createdAt = createdAt;
    }

    public static SocialIdentityLink create(SocialProvider provider, String providerSubject, UUID userId) {
        return new SocialIdentityLink(UUID.randomUUID(), provider, providerSubject, userId, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public SocialProvider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public UUID getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
