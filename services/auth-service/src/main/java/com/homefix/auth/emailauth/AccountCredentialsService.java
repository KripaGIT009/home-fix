package com.homefix.auth.emailauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.homefix.auth.api.UserNotFoundException;
import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.email.EmailDeliveryException;
import com.homefix.auth.email.EmailMessages;
import com.homefix.auth.email.EmailSenderPort;
import com.homefix.auth.password.LoginAttemptStore;
import com.homefix.auth.password.PasswordLoginException;

/**
 * A signed-in person's own credentials (email-auth Requirement 4): read them, add or change the
 * email (verified by an emailed code before it is saved), and set or change the password.
 *
 * <p>When the account already has a password, changing either requires the current one
 * (Requirement 4.2). Wrong current passwords count towards the same lockout as sign-in, keyed by
 * account, so this cannot be used to guess a password an attacker found a session for.
 */
@Service
public class AccountCredentialsService {

    private static final Logger log = LoggerFactory.getLogger(AccountCredentialsService.class);

    /** What {@code GET /auth/me} shows. */
    public record Credentials(UUID userId, String displayName, String email, boolean emailVerified,
                              String mobileNumber, String username, boolean hasPassword, List<String> roles) {

        static Credentials of(UserAccount account) {
            return new Credentials(account.getId(), account.getDisplayName(),
                    account.isEmailVerified() ? account.getEmail() : null, account.isEmailVerified(),
                    account.getMobileNumber(), account.getUsername(), account.hasPassword(),
                    account.getRoles().stream().map(Enum::name).sorted().toList());
        }
    }

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodes codes;
    private final EmailSenderPort emailSender;
    private final LoginAttemptStore attempts;
    private final PasswordLoginProperties passwordProperties;

    public AccountCredentialsService(UserAccountRepository userRepository, PasswordEncoder passwordEncoder,
                                     EmailCodes codes, EmailSenderPort emailSender, LoginAttemptStore attempts,
                                     PasswordLoginProperties passwordProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.codes = codes;
        this.emailSender = emailSender;
        this.attempts = attempts;
        this.passwordProperties = passwordProperties;
    }

    public Credentials credentials(UUID userId) {
        return Credentials.of(load(userId));
    }

    /**
     * Emails a code to a new address for this account.
     *
     * @return seconds until the code expires
     * @throws EmailAuthException 403 {@code CURRENT_PASSWORD_INCORRECT}, 409 {@code EMAIL_IN_USE},
     *                            429, 502
     */
    public long requestEmailChange(UUID userId, String rawEmail, String currentPassword) {
        UserAccount account = loadActive(userId);
        requireCurrentPassword(account, currentPassword);
        String email = EmailAddresses.normalise(rawEmail);
        requireEmailAvailable(email, account);
        codes.requireSendAllowed(email);
        String code = codes.issue(CodePurpose.EMAIL_CHANGE, userId.toString(), email);
        try {
            emailSender.send(email, EmailMessages.emailChangeCode(code, codes.codeTtl()));
        } catch (EmailDeliveryException ex) {
            log.warn("Email-change code for account {} could not be sent: {}", userId, ex.getMessage());
            throw EmailAuthException.emailDeliveryFailed();
        }
        return codes.codeTtl().getSeconds();
    }

    /**
     * Saves the new address once its code is entered.
     *
     * @throws EmailAuthException 400 {@code INVALID_CODE}, 410 {@code CODE_EXPIRED}, 409
     *                            {@code EMAIL_IN_USE} if another account took it meanwhile
     */
    public Credentials confirmEmailChange(UUID userId, String code) {
        UserAccount account = loadActive(userId);
        String email = codes.consume(CodePurpose.EMAIL_CHANGE, userId.toString(), code)
                .orElseThrow(EmailAuthException::codeExpired);
        requireEmailAvailable(email, account);
        account.setVerifiedEmail(email, Instant.now());
        try {
            account = userRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException ex) {
            throw EmailAuthException.emailInUse();
        }
        log.info("Account {} verified a new email address", userId);
        return Credentials.of(account);
    }

    /**
     * Sets or changes the password. The account must have a verified email or a username to sign in
     * with, or the password could never be used.
     *
     * @throws EmailAuthException 400 {@code WEAK_PASSWORD} / {@code EMAIL_REQUIRED}, 403
     *                            {@code CURRENT_PASSWORD_INCORRECT}
     */
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        UserAccount account = loadActive(userId);
        if (!account.isEmailVerified() && account.getUsername() == null) {
            throw new EmailAuthException(HttpStatus.BAD_REQUEST, "EMAIL_REQUIRED",
                    "Add and verify an email address first, so you can sign in with the password.");
        }
        PasswordPolicy.requireAcceptable(newPassword);
        requireCurrentPassword(account, currentPassword);
        account.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(account);
        log.info("Account {} changed its password", userId);
    }

    /**
     * An address is available when no other account holds it, or only a sign-up nobody verified:
     * the person proving the address by code wins over an unverified claim on it.
     */
    private void requireEmailAvailable(String email, UserAccount self) {
        Optional<UserAccount> holder = userRepository.findByEmail(email)
                .filter(other -> !other.getId().equals(self.getId()));
        if (holder.isEmpty()) {
            return;
        }
        if (holder.get().getStatus() == AccountStatus.PENDING_VERIFICATION) {
            userRepository.delete(holder.get());
            userRepository.flush();
            return;
        }
        throw EmailAuthException.emailInUse();
    }

    private void requireCurrentPassword(UserAccount account, String currentPassword) {
        if (!account.hasPassword()) {
            return;
        }
        String key = "account:" + account.getId();
        if (attempts.isLocked(key)) {
            throw PasswordLoginException.locked(attempts.lockRemaining(key).getSeconds());
        }
        if (currentPassword != null && passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            attempts.clearFailures(key);
            return;
        }
        if (attempts.recordFailure(key, passwordProperties.getFailureWindow()) >= passwordProperties.getMaxAttempts()) {
            attempts.lock(key, passwordProperties.getLockout());
            throw PasswordLoginException.locked(passwordProperties.getLockout().getSeconds());
        }
        throw EmailAuthException.currentPasswordIncorrect();
    }

    private UserAccount loadActive(UUID userId) {
        UserAccount account = load(userId);
        AccountDisabledException.requireActive(account);
        return account;
    }

    private UserAccount load(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }
}
