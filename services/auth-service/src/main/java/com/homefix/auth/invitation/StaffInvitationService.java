package com.homefix.auth.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.auth.admin.StaffActor;
import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.email.EmailDeliveryException;
import com.homefix.auth.email.EmailMessages;
import com.homefix.auth.email.EmailSenderPort;
import com.homefix.auth.emailauth.EmailAddresses;
import com.homefix.auth.emailauth.EmailAuthException;
import com.homefix.auth.emailauth.EmailCodes;
import com.homefix.auth.emailauth.PasswordPolicy;
import com.homefix.auth.password.LoginAttemptStore;
import com.homefix.auth.password.PasswordLoginException;
import com.homefix.auth.password.PasswordLoginService.LoginResult;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Staff invitations (email-auth Requirement 6; Property EA3).
 *
 * <p>This is the only path in the feature that grants a staff role, and only an authorised admin can
 * open it: a SUPER_ADMIN may invite any of {@code ADMIN}, {@code FINANCE_ADMIN}, {@code DISPATCHER}
 * and {@code SUPPORT_AGENT}; an ADMIN may invite the last three. {@code SUPER_ADMIN} and
 * {@code TENANT_ADMIN} are never invitable.
 *
 * <p>The emailed link carries 32 random bytes; only their SHA-256 is stored, so the table alone
 * cannot be used to accept an invitation. An invitation works once, for 7 days by default, and
 * inviting the same address again revokes the open one first. Every invitation, acceptance and
 * revocation is logged with the acting account's id, never the email address (Requirement 6.6).
 */
@Service
public class StaffInvitationService {

    private static final Logger log = LoggerFactory.getLogger(StaffInvitationService.class);

    /** The roles an invitation may carry. */
    public static final Set<Role> INVITABLE = EnumSet.of(Role.ADMIN, Role.FINANCE_ADMIN, Role.DISPATCHER,
            Role.SUPPORT_AGENT);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** An open invitation as the Admin Portal lists it. */
    public record InvitationView(UUID id, String email, Role role, UUID invitedBy, String invitedByName,
                                 Instant createdAt, Instant expiresAt) {
    }

    /** What the invitee sees before accepting. */
    public record InvitationPreview(String email, Role role, String invitedByName, Instant expiresAt,
                                    boolean existingAccount) {
    }

    /**
     * An acceptance: display name and mobile number are needed only for a new account; the password
     * is the new account's, or the existing account's current one.
     */
    public record AcceptCommand(String displayName, String mobileNumber, String password) {
    }

    private final StaffInvitationRepository invitations;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EmailSenderPort emailSender;
    private final TokenService tokenService;
    private final EmailCodes codes;
    private final LoginAttemptStore loginAttempts;
    private final PasswordLoginProperties passwordProperties;
    private final EmailAuthProperties properties;

    public StaffInvitationService(StaffInvitationRepository invitations, UserAccountRepository users,
                                  PasswordEncoder passwordEncoder, EmailSenderPort emailSender,
                                  TokenService tokenService, EmailCodes codes, LoginAttemptStore loginAttempts,
                                  PasswordLoginProperties passwordProperties, EmailAuthProperties properties) {
        this.invitations = invitations;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.tokenService = tokenService;
        this.codes = codes;
        this.loginAttempts = loginAttempts;
        this.passwordProperties = passwordProperties;
        this.properties = properties;
    }

    /**
     * Invites an address with a staff role and emails the link.
     *
     * @throws EmailAuthException 400 {@code INVALID_ROLE}, 403 {@code SUPER_ADMIN_REQUIRED}, 502
     *                            {@code EMAIL_DELIVERY_FAILED} (nothing is saved then)
     */
    @Transactional
    public InvitationView invite(StaffActor actor, String rawEmail, String roleName) {
        Role role = invitableRole(roleName);
        requireMayManage(actor, role);
        String email = EmailAddresses.normalise(rawEmail);
        Instant now = Instant.now();

        // Re-inviting replaces the open invitation (Requirement 6.2).
        List<StaffInvitation> open = invitations.findOpenByEmail(email);
        open.forEach(previous -> previous.revoke(now));
        invitations.saveAllAndFlush(open);

        String token = newToken();
        StaffInvitation invitation = invitations.save(new StaffInvitation(email, role, sha256(token),
                actor.userId(), now, now.plus(properties.getInvitationTtl())));
        String inviterName = displayNameOf(actor.userId());
        try {
            emailSender.send(email, EmailMessages.invitation(inviterName, role.name(), link(token),
                    properties.getInvitationTtl()));
        } catch (EmailDeliveryException ex) {
            log.warn("Invitation email could not be sent: {}", ex.getMessage());
            throw EmailAuthException.emailDeliveryFailed();
        }
        log.info("Invitation {} for role {} created by account {}{}", invitation.getId(), role, actor.userId(),
                open.isEmpty() ? "" : " (replacing " + open.size() + " open invitation(s))");
        return view(invitation, inviterName);
    }

    /** Invitations whose link still works, newest first. */
    @Transactional(readOnly = true)
    public List<InvitationView> listUsable() {
        List<StaffInvitation> usable = invitations.findUsable(Instant.now());
        Map<UUID, String> names = users.findAllById(usable.stream().map(StaffInvitation::getInvitedBy)
                        .distinct().toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, StaffInvitationService::nameOf, (a, b) -> a));
        return usable.stream()
                .map(invitation -> view(invitation, names.getOrDefault(invitation.getInvitedBy(), "A HomeFix admin")))
                .toList();
    }

    /**
     * Revokes an open invitation; revoking one already closed changes nothing.
     *
     * @throws EmailAuthException 404 {@code INVITATION_NOT_FOUND}, 403 {@code SUPER_ADMIN_REQUIRED}
     *                            for an ADMIN invitation revoked by a non-super admin
     */
    @Transactional
    public void revoke(StaffActor actor, UUID invitationId) {
        StaffInvitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new EmailAuthException(HttpStatus.NOT_FOUND, "INVITATION_NOT_FOUND",
                        "No invitation with that id."));
        requireMayManage(actor, invitation.getRole());
        if (!invitation.isOpen()) {
            return;
        }
        invitation.revoke(Instant.now());
        invitations.save(invitation);
        log.info("Invitation {} revoked by account {}", invitationId, actor.userId());
    }

    /**
     * The invitation behind a link, for the acceptance page.
     *
     * @throws EmailAuthException 410 {@code INVITATION_EXPIRED} for an unknown, used, revoked or
     *                            expired link (one answer for all, so links cannot be probed)
     */
    @Transactional(readOnly = true)
    public InvitationPreview preview(String token) {
        StaffInvitation invitation = usable(token);
        boolean existing = users.findByEmail(invitation.getEmail())
                .filter(account -> account.getStatus() != AccountStatus.PENDING_VERIFICATION)
                .isPresent();
        return new InvitationPreview(invitation.getEmail(), invitation.getRole(),
                displayNameOf(invitation.getInvitedBy()), invitation.getExpiresAt(), existing);
    }

    /**
     * Accepts an invitation and signs the person in with the invited role (Requirement 6.3, 6.4). An
     * address that already has an account proves it with that account's password and gains the role;
     * otherwise a new account is created with the email verified, since the link proved it.
     *
     * @throws EmailAuthException 410 {@code INVITATION_EXPIRED}; 400 {@code WEAK_PASSWORD} /
     *                            {@code VALIDATION_ERROR}; 409 {@code MOBILE_IN_USE} /
     *                            {@code PASSWORD_NOT_SET}; 429
     * @throws PasswordLoginException 401 {@code INVALID_CREDENTIALS} for a wrong password on an
     *                                existing account, 429 while locked
     */
    @Transactional
    public LoginResult accept(String token, AcceptCommand command, String clientIp) {
        codes.requireIpAllowed("invitation-accept", clientIp);
        StaffInvitation invitation = usable(token);
        String email = invitation.getEmail();

        Optional<UserAccount> holder = users.findByEmail(email);
        if (holder.isPresent() && holder.get().getStatus() == AccountStatus.PENDING_VERIFICATION) {
            // An unverified sign-up proves nothing about the address; the invitation link does.
            users.delete(holder.get());
            users.flush();
            holder = Optional.empty();
        }
        UserAccount account = holder.isPresent()
                ? joinExisting(holder.get(), command)
                : createAccount(email, invitation.getRole(), command);
        account.addRole(invitation.getRole());
        account = users.saveAndFlush(account);

        if (invitations.markAccepted(invitation.getId(), account.getId(), Instant.now()) == 0) {
            // Accepted or revoked by a concurrent request: roll everything back.
            throw expired();
        }
        log.info("Invitation {} accepted by account {} with role {}", invitation.getId(), account.getId(),
                invitation.getRole());
        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        TokenPair tokens = tokenService.issueTokens(account.getId().toString(), roles);
        return new LoginResult(account.getId().toString(), roles, tokens);
    }

    private UserAccount joinExisting(UserAccount account, AcceptCommand command) {
        String key = account.getEmail();
        if (loginAttempts.isLocked(key)) {
            throw PasswordLoginException.locked(loginAttempts.lockRemaining(key).getSeconds());
        }
        if (!account.hasPassword()) {
            throw new EmailAuthException(HttpStatus.CONFLICT, "PASSWORD_NOT_SET",
                    "The account with this email has no password yet. Sign in to the HomeFix app, set a "
                            + "password under Email & password, then open this link again.");
        }
        if (command.password() == null || !passwordEncoder.matches(command.password(), account.getPasswordHash())) {
            if (loginAttempts.recordFailure(key, passwordProperties.getFailureWindow())
                    >= passwordProperties.getMaxAttempts()) {
                loginAttempts.lock(key, passwordProperties.getLockout());
                throw PasswordLoginException.locked(passwordProperties.getLockout().getSeconds());
            }
            throw PasswordLoginException.invalidCredentials();
        }
        loginAttempts.clearFailures(key);
        AccountDisabledException.requireActive(account);
        account.setDisplayNameIfAbsent(command.displayName());
        return account;
    }

    private UserAccount createAccount(String email, Role role, AcceptCommand command) {
        String name = command.displayName() == null ? "" : command.displayName().strip();
        String mobile = command.mobileNumber() == null ? "" : command.mobileNumber().strip();
        if (name.length() < 2 || name.length() > 80 || !mobile.matches("^\\+[1-9]\\d{7,14}$")) {
            throw new EmailAuthException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Enter your name (2-80 characters) and your mobile number with country code.");
        }
        PasswordPolicy.requireAcceptable(command.password());
        users.findByMobileNumber(mobile).ifPresent(other -> {
            throw EmailAuthException.mobileInUse();
        });
        UserAccount account = UserAccount.createInvited(name, email, mobile, role,
                passwordEncoder.encode(command.password()));
        try {
            return users.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            throw EmailAuthException.mobileInUse();
        }
    }

    private StaffInvitation usable(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            throw expired();
        }
        return invitations.findByTokenHash(sha256(token))
                .filter(invitation -> invitation.isUsable(Instant.now()))
                .orElseThrow(StaffInvitationService::expired);
    }

    private static EmailAuthException expired() {
        return new EmailAuthException(HttpStatus.GONE, "INVITATION_EXPIRED",
                "This invitation link has expired or was already used. Ask your admin for a new one.");
    }

    private static Role invitableRole(String roleName) {
        try {
            Role role = Role.valueOf(roleName == null ? "" : roleName.strip().toUpperCase(Locale.ROOT));
            if (INVITABLE.contains(role)) {
                return role;
            }
        } catch (IllegalArgumentException ex) {
            // same refusal as a role that exists but cannot be invited
        }
        throw new EmailAuthException(HttpStatus.BAD_REQUEST, "INVALID_ROLE",
                "An invitation can carry ADMIN, FINANCE_ADMIN, DISPATCHER or SUPPORT_AGENT.");
    }

    /** Only a SUPER_ADMIN may invite (or revoke an invitation for) an ADMIN (Requirement 6.1). */
    private static void requireMayManage(StaffActor actor, Role role) {
        if (role == Role.ADMIN && !actor.isSuperAdmin()) {
            throw new EmailAuthException(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED",
                    "Only a super admin can invite an admin.");
        }
    }

    private String link(String token) {
        String base = properties.getAdminPortalUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/invite/" + token;
    }

    private String displayNameOf(UUID userId) {
        return users.findById(userId).map(StaffInvitationService::nameOf).orElse("A HomeFix admin");
    }

    private static String nameOf(UserAccount account) {
        if (account.getDisplayName() != null) {
            return account.getDisplayName();
        }
        return account.getUsername() != null ? account.getUsername() : "A HomeFix admin";
    }

    private static InvitationView view(StaffInvitation invitation, String inviterName) {
        return new InvitationView(invitation.getId(), invitation.getEmail(), invitation.getRole(),
                invitation.getInvitedBy(), inviterName, invitation.getCreatedAt(), invitation.getExpiresAt());
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
