package com.homefix.auth.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

/**
 * A verified platform user account created on successful OTP verification (Requirement 1.2).
 *
 * <p>The mobile number is the natural identity for OTP-based registration and is stored
 * as a unique key. A single account may carry multiple {@link Role}s (Requirement 1.14).
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Mobile number is the natural identity for OTP registration. It is nullable because a
     * social-login account (Requirement 1.5) may be created without one. When present it is
     * unique.
     */
    @Column(name = "mobile_number", unique = true, length = 20)
    private String mobileNumber;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    /**
     * Optional console username for password sign-in. Staff use the Admin Portal, which has
     * no phone to hand, so an account may additionally carry a username and a bcrypt hash.
     * Both are nullable: an OTP-only or social account has neither, and an account with a
     * username but no hash cannot sign in with a password.
     */
    @Column(name = "username", unique = true, length = 64)
    private String username;

    /** Bcrypt hash (cost >= 12) of the console password. Never the password itself. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /**
     * Batch-fetched so the Admin Portal's user list (up to 200 accounts) loads every account's
     * roles in a couple of queries instead of one per account.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "user_account_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    /**
     * Whether the account may authenticate (Requirement 19.2). Every factory starts it
     * {@link AccountStatus#ACTIVE}; only an administrator changes it, through
     * {@link #changeStatus}. Rows that predate the column were backfilled ACTIVE by V2.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected UserAccount() {
        // JPA
    }

    private UserAccount(UUID id, String mobileNumber, boolean verified, Set<Role> roles, Instant createdAt) {
        this.id = id;
        this.mobileNumber = mobileNumber;
        this.verified = verified;
        this.roles = roles;
        this.createdAt = createdAt;
    }

    /**
     * Factory for a freshly verified account with a single initial role.
     */
    public static UserAccount createVerified(String mobileNumber, Role role) {
        return new UserAccount(
                UUID.randomUUID(),
                mobileNumber,
                true,
                EnumSet.of(role),
                Instant.now());
    }

    /**
     * Factory for an account created via social login (Requirement 1.5). Such accounts are
     * verified (the provider asserted the identity) and carry no mobile number.
     */
    public static UserAccount createSocial(Role role) {
        return new UserAccount(
                UUID.randomUUID(),
                null,
                true,
                EnumSet.of(role),
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public boolean isVerified() {
        return verified;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    /**
     * Adds a role to the account.
     *
     * @return true if the account did not already hold it
     */
    public boolean addRole(Role role) {
        return this.roles.add(role);
    }

    /**
     * Removes a role from the account. Only {@code AccountRoleService} calls this, for the
     * roles it may manage (Requirement MT-2.4).
     *
     * @return true if the account held it
     */
    public boolean removeRole(Role role) {
        return this.roles.remove(role);
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Whether this account can be signed in to with a username and password. An account
     * carrying a username but no hash (or the reverse) cannot, so both are required.
     */
    public boolean hasPasswordCredentials() {
        return username != null && passwordHash != null;
    }

    /**
     * Attaches or replaces the console credentials. The caller passes an already-encoded
     * hash: this entity never sees a raw password, so it can never store one by accident.
     */
    public void setCredentials(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AccountStatus getStatus() {
        return status;
    }

    /**
     * Sets the account's status. Who may do this, and the consequences for live sessions, are
     * the caller's concern ({@code AdminUserService}); the entity only records the new value.
     */
    public void changeStatus(AccountStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status");
    }
}
