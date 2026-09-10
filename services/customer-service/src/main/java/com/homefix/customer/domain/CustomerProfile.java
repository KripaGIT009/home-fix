package com.homefix.customer.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A customer's profile (Requirement 2.1, design {@code CUSTOMER_PROFILE}).
 *
 * <p>The e-mail is PII and is therefore persisted only in the {@code email_encrypted}
 * column as an AES-256 ciphertext produced by the {@code KmsEncryptionPort}
 * (Requirement 2.7, 26.3). The plaintext e-mail never touches the database. The display
 * name is likewise a directly-encrypted PII field.
 */
@Entity
@Table(name = "customer_profile")
public class CustomerProfile {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The owning user account id (from Auth Service). */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    /** Display name, encrypted at rest (PII). */
    @Column(name = "display_name_encrypted", length = 1024)
    private String displayNameEncrypted;

    /** E-mail address, encrypted at rest (PII). */
    @Column(name = "email_encrypted", length = 1024)
    private String emailEncrypted;

    @Column(name = "photo_url", length = 2048)
    private String photoUrl;

    /** True once PII has been anonymized following a data-deletion request. */
    @Column(name = "anonymized", nullable = false)
    private boolean anonymized;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerProfile() {
        // JPA
    }

    private CustomerProfile(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
        this.anonymized = false;
        this.updatedAt = Instant.now();
    }

    /**
     * Factory for a new, empty profile bound to a user account.
     */
    public static CustomerProfile forUser(UUID userId) {
        return new CustomerProfile(UUID.randomUUID(), userId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayNameEncrypted() {
        return displayNameEncrypted;
    }

    public void setDisplayNameEncrypted(String displayNameEncrypted) {
        this.displayNameEncrypted = displayNameEncrypted;
        touch();
    }

    public String getEmailEncrypted() {
        return emailEncrypted;
    }

    public void setEmailEncrypted(String emailEncrypted) {
        this.emailEncrypted = emailEncrypted;
        touch();
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
        touch();
    }

    public boolean isAnonymized() {
        return anonymized;
    }

    /**
     * Replaces PII with non-reversible anonymized tokens (Requirement 26.9).
     */
    public void anonymize(String anonymizedNameToken, String anonymizedEmailToken) {
        this.displayNameEncrypted = anonymizedNameToken;
        this.emailEncrypted = anonymizedEmailToken;
        this.photoUrl = null;
        this.anonymized = true;
        touch();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
