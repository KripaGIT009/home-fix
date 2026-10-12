package com.homefix.auth.emailauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.email.EmailDeliveryException;
import com.homefix.auth.email.EmailMessage;
import com.homefix.auth.email.EmailMessages;
import com.homefix.auth.email.EmailSenderPort;
import com.homefix.auth.password.PasswordLoginService.LoginResult;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Email sign-up for customers and providers (email-auth Requirement 1).
 *
 * <p>{@link #register} creates a {@link AccountStatus#PENDING_VERIFICATION} account and emails a
 * code; {@link #verify} activates it and signs the person in; {@link #resend} sends a fresh code.
 *
 * <p>Enumeration safety (Property EA1): {@code register} and {@code resend} answer the same whether
 * the address is new, pending or already an account. An address that already has an account gets a
 * "you already have an account" email instead of a code, and nothing is created or changed. The
 * password is hashed on every request, so the bcrypt cost does not separate the cases by timing.
 * The one answer that differs is {@code 409 MOBILE_IN_USE}, which the product owner chose
 * (Requirement 1.5).
 *
 * <p>Only {@code CUSTOMER} and {@code SERVICE_PROVIDER} can be requested; every other role is
 * {@code 400 INVALID_ROLE} (Property EA3).
 */
@Service
public class EmailSignupService {

    private static final Logger log = LoggerFactory.getLogger(EmailSignupService.class);

    /** A sign-up request, validated for shape by the REST layer. */
    public record SignupCommand(String displayName, String email, String mobileNumber, String password,
                                String role) {
    }

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodes codes;
    private final EmailSenderPort emailSender;
    private final TokenService tokenService;
    private final EmailAuthProperties properties;

    public EmailSignupService(UserAccountRepository userRepository, PasswordEncoder passwordEncoder,
                              EmailCodes codes, EmailSenderPort emailSender, TokenService tokenService,
                              EmailAuthProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.codes = codes;
        this.emailSender = emailSender;
        this.tokenService = tokenService;
        this.properties = properties;
    }

    /**
     * Starts a sign-up.
     *
     * @return seconds until the emailed code expires
     * @throws EmailAuthException 400 {@code INVALID_ROLE} / {@code WEAK_PASSWORD}, 409
     *                            {@code MOBILE_IN_USE}, 429 {@code TOO_MANY_REQUESTS}, 502
     *                            {@code EMAIL_DELIVERY_FAILED}
     */
    public long register(SignupCommand command, String clientIp) {
        Role role = selfServiceRole(command.role());
        PasswordPolicy.requireAcceptable(command.password());
        String email = EmailAddresses.normalise(command.email());
        String displayName = command.displayName().strip();
        String mobile = command.mobileNumber().strip();

        codes.requireIpAllowed("signup", clientIp);
        codes.requireSendAllowed(email);
        String passwordHash = passwordEncoder.encode(command.password());

        Optional<UserAccount> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent() && byEmail.get().getStatus() != AccountStatus.PENDING_VERIFICATION) {
            send(email, EmailMessages.alreadyRegistered());
            return codes.codeTtl().getSeconds();
        }

        UserAccount pending = byEmail.filter(account -> !isStale(account)).orElse(null);
        if (byEmail.isPresent() && pending == null) {
            removeStale(byEmail.get());
        }
        requireMobileFree(mobile, pending);

        if (pending == null) {
            pending = UserAccount.createPendingEmailSignup(displayName, email, mobile, role, passwordHash);
        } else {
            pending.replacePendingSignup(displayName, mobile, role, passwordHash);
        }
        try {
            pending = userRepository.saveAndFlush(pending);
        } catch (DataIntegrityViolationException ex) {
            // Another sign-up took the address or the number between our checks and the insert.
            throw new EmailAuthException(HttpStatus.CONFLICT, "SIGNUP_CONFLICT",
                    "This sign-up clashed with another one. Please try again.");
        }

        String code = codes.issue(CodePurpose.SIGNUP, email, null);
        send(email, EmailMessages.signupCode(displayName, code, codes.codeTtl()));
        log.info("Email sign-up {} started with role {}", pending.getId(), role);
        return codes.codeTtl().getSeconds();
    }

    /**
     * Finishes a sign-up with its emailed code: the account becomes ACTIVE with its email verified,
     * and is signed in (Requirement 1.6).
     *
     * @throws EmailAuthException 400 {@code INVALID_CODE}, 410 {@code CODE_EXPIRED}
     */
    public LoginResult verify(String rawEmail, String code) {
        String email = EmailAddresses.normalise(rawEmail);
        codes.consume(CodePurpose.SIGNUP, email, code);
        UserAccount account = userRepository.findByEmail(email).orElseThrow(EmailAuthException::codeExpired);
        if (account.getStatus() == AccountStatus.PENDING_VERIFICATION || !account.isEmailVerified()) {
            account.completeEmailVerification(Instant.now());
            account = userRepository.save(account);
            log.info("Email sign-up {} verified", account.getId());
        }
        AccountDisabledException.requireActive(account);
        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        TokenPair tokens = tokenService.issueTokens(account.getId().toString(), roles);
        return new LoginResult(account.getId().toString(), roles, tokens);
    }

    /**
     * Sends a fresh sign-up code if the address has a sign-up waiting; otherwise sends nothing. The
     * answer is the same either way.
     *
     * @return seconds until a code would expire
     * @throws EmailAuthException 429 {@code TOO_MANY_REQUESTS}
     */
    public long resend(String rawEmail, String clientIp) {
        String email = EmailAddresses.normalise(rawEmail);
        codes.requireIpAllowed("resend", clientIp);
        codes.requireSendAllowed(email);
        userRepository.findByEmail(email)
                .filter(account -> account.getStatus() == AccountStatus.PENDING_VERIFICATION)
                .filter(account -> !isStale(account))
                .ifPresent(account -> {
                    String code = codes.issue(CodePurpose.SIGNUP, email, null);
                    send(email, EmailMessages.signupCode(account.getDisplayName(), code, codes.codeTtl()));
                });
        return codes.codeTtl().getSeconds();
    }

    /**
     * The number must not belong to another account. A number held only by a sign-up left
     * unverified past its lifetime is freed instead (Requirement 1.7).
     */
    private void requireMobileFree(String mobile, UserAccount ownPending) {
        Optional<UserAccount> holder = userRepository.findByMobileNumber(mobile)
                .filter(other -> ownPending == null || !other.getId().equals(ownPending.getId()));
        if (holder.isEmpty()) {
            return;
        }
        if (holder.get().getStatus() == AccountStatus.PENDING_VERIFICATION && isStale(holder.get())) {
            removeStale(holder.get());
            return;
        }
        throw EmailAuthException.mobileInUse();
    }

    private boolean isStale(UserAccount account) {
        return account.getStatus() == AccountStatus.PENDING_VERIFICATION
                && account.getCreatedAt().isBefore(Instant.now().minus(properties.getPendingSignupTtl()));
    }

    private void removeStale(UserAccount account) {
        userRepository.delete(account);
        userRepository.flush();
        log.info("Removed unverified sign-up {} older than {}", account.getId(), properties.getPendingSignupTtl());
    }

    private void send(String email, EmailMessage message) {
        try {
            emailSender.send(email, message);
        } catch (EmailDeliveryException ex) {
            log.warn("Sign-up email \"{}\" could not be sent: {}", message.subject(), ex.getMessage());
            throw EmailAuthException.emailDeliveryFailed();
        }
    }

    private static Role selfServiceRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Role.CUSTOMER;
        }
        try {
            Role role = Role.valueOf(roleName.strip().toUpperCase(java.util.Locale.ROOT));
            if (role == Role.CUSTOMER || role == Role.SERVICE_PROVIDER) {
                return role;
            }
        } catch (IllegalArgumentException ex) {
            // falls through to the same refusal as a privileged role
        }
        log.warn("Email sign-up asked for a role that is not self-service; refusing");
        throw EmailAuthException.invalidRole();
    }
}
