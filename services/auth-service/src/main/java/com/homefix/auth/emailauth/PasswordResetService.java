package com.homefix.auth.emailauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.email.EmailDeliveryException;
import com.homefix.auth.email.EmailMessages;
import com.homefix.auth.email.EmailSenderPort;
import com.homefix.auth.password.LoginAttemptStore;
import com.homefix.auth.token.TokenService;

/**
 * Password reset by emailed code (email-auth Requirement 3).
 *
 * <p>{@link #requestReset} answers the same for every address (Property EA1) and emails a code only
 * to an active account whose email is verified. {@link #reset} replaces the password, revokes every
 * refresh-token family of the account so sessions on other devices end (Property EA5), and lifts
 * any sign-in lockout on the address.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodes codes;
    private final EmailSenderPort emailSender;
    private final TokenService tokenService;
    private final LoginAttemptStore loginAttempts;

    public PasswordResetService(UserAccountRepository userRepository, PasswordEncoder passwordEncoder,
                                EmailCodes codes, EmailSenderPort emailSender, TokenService tokenService,
                                LoginAttemptStore loginAttempts) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.codes = codes;
        this.emailSender = emailSender;
        this.tokenService = tokenService;
        this.loginAttempts = loginAttempts;
    }

    /**
     * Emails a reset code if an active account has this verified address.
     *
     * @return seconds a code would stay valid (returned in every case)
     * @throws EmailAuthException 429 {@code TOO_MANY_REQUESTS}
     */
    public long requestReset(String rawEmail, String clientIp) {
        String email = EmailAddresses.normalise(rawEmail);
        codes.requireIpAllowed("reset", clientIp);
        codes.requireSendAllowed(email);
        userRepository.findByEmail(email)
                .filter(account -> account.getStatus() == AccountStatus.ACTIVE)
                .filter(UserAccount::isEmailVerified)
                .ifPresent(account -> {
                    String code = codes.issue(CodePurpose.RESET, email, null);
                    try {
                        emailSender.send(email, EmailMessages.resetCode(code, codes.codeTtl()));
                    } catch (EmailDeliveryException ex) {
                        // Answered like every other request: a different status would tell the caller
                        // the address has an account.
                        log.warn("Password reset email for account {} could not be sent: {}",
                                account.getId(), ex.getMessage());
                    }
                });
        return codes.codeTtl().getSeconds();
    }

    /**
     * Sets a new password with a reset code.
     *
     * @throws EmailAuthException 400 {@code WEAK_PASSWORD} (checked first, so a weak password does
     *                            not spend the code) or {@code INVALID_CODE}; 410 {@code CODE_EXPIRED}
     */
    public void reset(String rawEmail, String code, String newPassword) {
        PasswordPolicy.requireAcceptable(newPassword);
        String email = EmailAddresses.normalise(rawEmail);
        codes.consume(CodePurpose.RESET, email, code);
        UserAccount account = userRepository.findByEmail(email)
                .filter(found -> found.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(EmailAuthException::codeExpired);
        account.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(account);
        // After the new password is committed, so a refresh racing the reset cannot outlive it.
        tokenService.revokeAllRefreshTokens(account.getId().toString());
        loginAttempts.unlock(email);
        log.info("Password reset for account {}; its sessions were ended", account.getId());
    }
}
