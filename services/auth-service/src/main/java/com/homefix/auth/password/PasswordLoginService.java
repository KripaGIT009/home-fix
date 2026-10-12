package com.homefix.auth.password;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.emailauth.EmailAddresses;
import com.homefix.auth.emailauth.EmailAuthException;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Password sign-in with an email address or a console username (email-auth Requirement 2).
 *
 * <p>No account is created here and no role is ever named by the caller: the roles returned are
 * whatever the stored account already holds, so a password can never be used to escalate. An
 * identifier containing {@code @} is looked up as an email, anything else as a username; both are
 * case-insensitive.
 *
 * <p>Three defences apply on every call:
 * <ul>
 *   <li><b>No user enumeration.</b> An unknown username, an account with no password set and
 *       a wrong password all return the same 401. The encoder is run against a dummy hash for
 *       the first two cases so the response time does not separate them either.</li>
 *   <li><b>Lockout.</b> Consecutive failures are counted per username and lock it for the
 *       configured window, matching the OTP flow's defence (Requirement 1.3).</li>
 *   <li><b>Bcrypt.</b> Verification goes through the shared cost-12 encoder; the raw password
 *       is never stored, logged or echoed.</li>
 * </ul>
 */
@Service
public class PasswordLoginService {

    private static final Logger log = LoggerFactory.getLogger(PasswordLoginService.class);

    /**
     * A real bcrypt hash of a value no caller can supply, verified against when the account
     * or its credentials are missing. Without this the lookup miss would return in
     * microseconds while a real check spends bcrypt's full cost, which is itself an oracle
     * for which usernames exist.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.3ciXLlmGtzYb5T0Hs1Q1PmGtJ1UvQUu";

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptStore attemptStore;
    private final PasswordLoginProperties properties;

    public PasswordLoginService(UserAccountRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                TokenService tokenService,
                                LoginAttemptStore attemptStore,
                                PasswordLoginProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.attemptStore = attemptStore;
        this.properties = properties;
    }

    /**
     * Authenticates an email or username and a password, returning the account's id, its current
     * roles and a fresh token pair.
     *
     * @throws PasswordLoginException 401 on any credential failure, 429 while locked
     * @throws EmailAuthException 403 {@code EMAIL_NOT_VERIFIED} if the password is right but the
     *                            account is an email sign-up whose code was never entered
     * @throws AccountDisabledException 403 if the credentials are correct but an administrator
     *                                  has suspended or deactivated the account
     */
    @Transactional(readOnly = true)
    public LoginResult authenticate(String rawIdentifier, String rawPassword) {
        String username = normalise(rawIdentifier);

        if (attemptStore.isLocked(username)) {
            throw PasswordLoginException.locked(attemptStore.lockRemaining(username).getSeconds());
        }

        Optional<UserAccount> maybeAccount = (EmailAddresses.looksLikeEmail(username)
                ? userRepository.findByEmail(username)
                : userRepository.findByUsername(username))
                .filter(UserAccount::hasPasswordCredentials);

        // Always spend the bcrypt cost, present account or not, so timing reveals nothing.
        String hash = maybeAccount.map(UserAccount::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(
                rawPassword == null ? "" : rawPassword, hash);

        if (maybeAccount.isEmpty() || !matches) {
            registerFailure(username);
            throw PasswordLoginException.invalidCredentials();
        }

        UserAccount account = maybeAccount.get();
        attemptStore.clearFailures(username);

        // Checked only once the password has matched: a wrong password on a suspended or unverified
        // account is the same 401 as on any other, so the status is never an enumeration oracle.
        if (account.getStatus() == AccountStatus.PENDING_VERIFICATION) {
            throw new EmailAuthException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                    "Verify your email first: enter the code we sent you, or ask for a new one.");
        }
        AccountDisabledException.requireActive(account);

        List<String> roles = account.getRoles().stream().map(Enum::name).sorted().toList();
        TokenPair tokens = tokenService.issueTokens(account.getId().toString(), roles);
        return new LoginResult(account.getId().toString(), roles, tokens);
    }

    /**
     * Counts a failure and locks the username once it reaches the configured threshold.
     * The username is never logged, because a mistyped password often <em>is</em> the
     * password typed into the username box.
     */
    private void registerFailure(String username) {
        int failures = attemptStore.recordFailure(username, properties.getFailureWindow());
        if (failures >= properties.getMaxAttempts()) {
            attemptStore.lock(username, properties.getLockout());
            log.warn("Password sign-in locked after {} consecutive failures", failures);
            throw PasswordLoginException.locked(properties.getLockout().getSeconds());
        }
    }

    /**
     * Usernames and emails are case-insensitive and stored lower case; trim so a stray space is not
     * a failure. Lockout is per normalised identifier (Requirement 2.2).
     */
    private String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Outcome of a successful sign-in — the same triple the OTP flow returns, so the REST
     * layer can reuse {@code TokenResponse} unchanged.
     */
    public record LoginResult(String userId, List<String> roles, TokenPair tokens) {
    }
}
