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
 *
 * <p>An account may also carry an email address and a password (email-auth spec): created by
 * email sign-up ({@link #createPendingEmailSignup}, verified by an emailed code), added to an OTP
 * account from the profile, or set when a staff invitation is accepted. The email is stored lower
 * case and is unique.
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
    @Column(name = "status", nullable = false, length = 24)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Lower-case email address, unique; null for an account without one. */
    @Column(name = "email", length = 254)
    private String email;

    /** When the email was proven by an emailed code or an invitation link; null if never. */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /** The name the person gave at email sign-up or invitation; null for OTP and social accounts. */
    @Column(name = "display_name", length = 80)
    private String displayName;

    /**
     * When the mobile number was proven by OTP. Null for a number given at email sign-up, which is
     * recorded for notifications but never verified there: OTP sign-in does not treat such a number
     * as belonging to this account (see {@code RegistrationService}).
     */
    @Column(name = "mobile_verified_at")
    private Instant mobileVerifiedAt;

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
        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                mobileNumber,
                true,
                EnumSet.of(role),
                Instant.now());
        account.mobileVerifiedAt = mobileNumber == null ? null : account.createdAt;
        return account;
    }

    /**
     * Factory for an email sign-up awaiting its emailed code (email-auth Requirement 1.3). It is
     * {@link AccountStatus#PENDING_VERIFICATION}, so it cannot authenticate until
     * {@link #completeEmailVerification} runs. The mobile number is recorded but not verified.
     *
     * @param email        lower-case email address
     * @param passwordHash bcrypt hash; never the raw password
     */
    public static UserAccount createPendingEmailSignup(String displayName, String email, String mobileNumber,
                                                       Role role, String passwordHash) {
        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                mobileNumber,
                false,
                EnumSet.of(role),
                Instant.now());
        account.displayName = displayName;
        account.email = email;
        account.passwordHash = passwordHash;
        account.status = AccountStatus.PENDING_VERIFICATION;
        return account;
    }

    /**
     * Factory for a staff member accepting an invitation (email-auth Requirement 6.3). The link
     * proved control of the address, so the email is verified at once; the mobile number is not.
     */
    public static UserAccount createInvited(String displayName, String email, String mobileNumber,
                                            Role role, String passwordHash) {
        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                mobileNumber,
                true,
                EnumSet.of(role),
                Instant.now());
        account.displayName = displayName;
        account.email = email;
        account.emailVerifiedAt = account.createdAt;
        account.passwordHash = passwordHash;
        return account;
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
     * Whether this account can be signed in to with a password: it needs a hash and something to
     * sign in with, a username or an email address.
     */
    public boolean hasPasswordCredentials() {
        return passwordHash != null && (username != null || email != null);
    }

    /** Whether a password is set at all (changing email or password then needs it confirmed). */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    /** Replaces the password hash. The caller passes an already-encoded hash. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    }

    public String getEmail() {
        return email;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public boolean isEmailVerified() {
        return email != null && emailVerifiedAt != null;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getMobileVerifiedAt() {
        return mobileVerifiedAt;
    }

    /** Whether the stored mobile number was proven by OTP. */
    public boolean isMobileVerified() {
        return mobileNumber != null && mobileVerifiedAt != null;
    }

    /**
     * Activates an email sign-up once its code is entered (email-auth Requirement 1.6). Only a
     * {@link AccountStatus#PENDING_VERIFICATION} account changes status; an account an
     * administrator has disabled meanwhile stays as it is.
     */
    public void completeEmailVerification(Instant at) {
        if (status == AccountStatus.PENDING_VERIFICATION) {
            status = AccountStatus.ACTIVE;
        }
        this.verified = true;
        this.emailVerifiedAt = at;
    }

    /** Sets a verified email address (profile change, or a seeded account). Lower case. */
    public void setVerifiedEmail(String email, Instant at) {
        this.email = Objects.requireNonNull(email, "email");
        this.emailVerifiedAt = at;
    }

    /**
     * Replaces the details of a pending sign-up when the same address signs up again before
     * verifying (email-auth design: "create or replace a PENDING_VERIFICATION account").
     */
    public void replacePendingSignup(String displayName, String mobileNumber, Role role, String passwordHash) {
        if (status != AccountStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException("Only a pending sign-up can be replaced");
        }
        this.displayName = displayName;
        this.mobileNumber = mobileNumber;
        this.mobileVerifiedAt = null;
        this.passwordHash = passwordHash;
        this.roles.clear();
        this.roles.add(role);
    }

    /** Sets the display name if the account has none (an existing account accepting an invitation). */
    public void setDisplayNameIfAbsent(String displayName) {
        if (this.displayName == null && displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
    }

    /** Records that OTP has just proven this account's mobile number. */
    public void markMobileVerified(Instant at) {
        if (mobileNumber != null) {
            this.mobileVerifiedAt = at;
        }
    }

    /**
     * Gives up a mobile number that was never verified, because someone else has just proven it by
     * OTP. The account keeps everything else and signs in by email as before.
     */
    public void releaseUnverifiedMobile() {
        if (mobileVerifiedAt != null) {
            throw new IllegalStateException("A verified mobile number is not released");
        }
        this.mobileNumber = null;
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
