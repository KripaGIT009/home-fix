package com.homefix.auth.password;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Username and password sign-in for accounts that carry console credentials.
 *
 * <p>This is the staff path. OTP registration remains the only way to <em>create</em> an
 * account, and no role is ever named by the caller here: the roles returned are whatever the
 * stored account already holds. A password can therefore never be used to escalate, only to
 * authenticate an account someone else provisioned.
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
     * Authenticates a username and password, returning the account's id, its current roles
     * and a fresh token pair.
     *
     * @throws PasswordLoginException 401 on any credential failure, 429 while locked
     * @throws AccountDisabledException 403 if the credentials are correct but an administrator
     *                                  has suspended or deactivated the account
     */
    @Transactional(readOnly = true)
    public LoginResult authenticate(String rawUsername, String rawPassword) {
        String username = normalise(rawUsername);

        if (attemptStore.isLocked(username)) {
            throw PasswordLoginException.locked(attemptStore.lockRemaining(username).getSeconds());
        }

        Optional<UserAccount> maybeAccount = userRepository.findByUsername(username)
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

        // Checked only once the password has matched: a wrong password on a suspended account
        // is the same 401 as on any other, so the status is never an enumeration oracle.
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

    /** Usernames are case-insensitive and stored lower case; trim so a stray space is not a failure. */
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
