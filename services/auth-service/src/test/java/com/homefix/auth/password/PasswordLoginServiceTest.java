package com.homefix.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.password.PasswordLoginService.LoginResult;
import com.homefix.auth.support.InMemoryLoginAttemptStore;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Unit tests for username/password sign-in.
 *
 * <p>Uses a real bcrypt encoder rather than a mock: the point of several of these tests is
 * that a stored hash verifies (and a wrong password does not), which a stubbed matcher would
 * assert nothing about. Cost 4 keeps the suite fast — the production cost is asserted
 * separately in {@code PasswordEncoderConfigTest}.
 */
@ExtendWith(MockitoExtension.class)
class PasswordLoginServiceTest {

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PHONE = "+919000000021";

    private PasswordEncoder encoder;
    private InMemoryLoginAttemptStore attemptStore;
    private PasswordLoginProperties properties;

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private TokenService tokenService;

    private PasswordLoginService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        attemptStore = new InMemoryLoginAttemptStore();

        properties = new PasswordLoginProperties();
        properties.setMaxAttempts(5);
        properties.setLockout(Duration.ofMinutes(30));
        properties.setFailureWindow(Duration.ofMinutes(15));

        service = new PasswordLoginService(userRepository, encoder, tokenService, attemptStore,
                properties);
    }

    /** An account with credentials attached, as the seeder or an administrator would create. */
    private UserAccount accountWithCredentials(String username, String rawPassword, Role role) {
        UserAccount account = UserAccount.createVerified(PHONE, role);
        account.setCredentials(username, encoder.encode(rawPassword));
        return account;
    }

    private void stubFound(UserAccount account) {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(account));
    }

    @Test
    void authenticatesWithCorrectCredentialsAndReturnsTheAccountsRoles() {
        UserAccount account = accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN);
        stubFound(account);
        when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));

        LoginResult result = service.authenticate(USERNAME, PASSWORD);

        assertThat(result.userId()).isEqualTo(account.getId().toString());
        assertThat(result.roles()).containsExactly("ADMIN");
        assertThat(result.tokens().accessToken()).isEqualTo("access");
    }

    /**
     * The caller never names a role, so a password can authenticate but never escalate: the
     * roles returned are exactly the ones already stored on the account.
     */
    @Test
    void returnsEveryRoleTheAccountHoldsSorted() {
        UserAccount account = accountWithCredentials(USERNAME, PASSWORD, Role.SUPER_ADMIN);
        account.addRole(Role.ADMIN);
        stubFound(account);
        when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));

        assertThat(service.authenticate(USERNAME, PASSWORD).roles())
                .containsExactly("ADMIN", "SUPER_ADMIN");
    }

    @Test
    void rejectsAWrongPasswordWithoutIssuingTokens() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));

        assertThatThrownBy(() -> service.authenticate(USERNAME, "wrong"))
                .isInstanceOf(PasswordLoginException.class)
                .extracting(ex -> ((PasswordLoginException) ex).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }

    /**
     * An unknown username and a wrong password must be indistinguishable, or the endpoint
     * becomes a way to discover which usernames exist.
     */
    @Test
    void answersAnUnknownUsernameExactlyAsItAnswersAWrongPassword() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());
        PasswordLoginException unknown = catchLoginException(() -> service.authenticate("nobody", PASSWORD));

        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));
        PasswordLoginException wrongPassword =
                catchLoginException(() -> service.authenticate(USERNAME, "wrong"));

        assertThat(unknown.getStatus()).isEqualTo(wrongPassword.getStatus());
        assertThat(unknown.getErrorCode()).isEqualTo(wrongPassword.getErrorCode());
        assertThat(unknown.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    /**
     * An OTP-only or social account has a null hash. It must be refused, and refused the same
     * way — never accepted because "no password" happened to match an empty submission.
     */
    @Test
    void refusesAnAccountThatHasNoPasswordSet() {
        UserAccount otpOnly = UserAccount.createVerified(PHONE, Role.CUSTOMER);
        assertThat(otpOnly.hasPasswordCredentials()).isFalse();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(otpOnly));

        assertThatThrownBy(() -> service.authenticate(USERNAME, ""))
                .isInstanceOf(PasswordLoginException.class)
                .hasMessageContaining("Incorrect username or password");
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "Admin", "  admin  "})
    void treatsUsernamesAsCaseInsensitiveAndTrimsSurroundingSpace(String supplied) {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));
        when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));

        assertThat(service.authenticate(supplied, PASSWORD).roles()).containsExactly("ADMIN");
    }

    @Test
    void locksTheUsernameOnTheConfiguredConsecutiveFailure() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));

        for (int attempt = 1; attempt < properties.getMaxAttempts(); attempt++) {
            assertThat(catchLoginException(() -> service.authenticate(USERNAME, "wrong"))
                    .getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
        }

        PasswordLoginException locked =
                catchLoginException(() -> service.authenticate(USERNAME, "wrong"));
        assertThat(locked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getErrorCode()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(locked.getRetryAfterSeconds())
                .isEqualTo(properties.getLockout().getSeconds());
    }

    /** While locked, even the right password is refused — otherwise the lock buys nothing. */
    @Test
    void refusesTheCorrectPasswordWhileLocked() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));
        for (int attempt = 0; attempt < properties.getMaxAttempts(); attempt++) {
            catchLoginException(() -> service.authenticate(USERNAME, "wrong"));
        }

        assertThat(catchLoginException(() -> service.authenticate(USERNAME, PASSWORD))
                .getErrorCode()).isEqualTo("ACCOUNT_LOCKED");
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }

    @Test
    void acceptsTheCorrectPasswordOnceTheLockoutWindowHasElapsed() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));
        for (int attempt = 0; attempt < properties.getMaxAttempts(); attempt++) {
            catchLoginException(() -> service.authenticate(USERNAME, "wrong"));
        }

        attemptStore.advance(properties.getLockout().plusSeconds(1));
        when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));

        assertThat(service.authenticate(USERNAME, PASSWORD).roles()).containsExactly("ADMIN");
    }

    /**
     * Failures must accumulate only while they are consecutive within the window. Four
     * failures, a wait, then four more should not lock: otherwise a user who mistypes
     * occasionally over a long session is eventually locked out for no reason.
     */
    @Test
    void doesNotCarryFailuresAcrossTheFailureWindow() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));

        for (int attempt = 0; attempt < properties.getMaxAttempts() - 1; attempt++) {
            catchLoginException(() -> service.authenticate(USERNAME, "wrong"));
        }
        attemptStore.advance(properties.getFailureWindow().plusSeconds(1));
        for (int attempt = 0; attempt < properties.getMaxAttempts() - 1; attempt++) {
            assertThat(catchLoginException(() -> service.authenticate(USERNAME, "wrong"))
                    .getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
        }

        assertThat(attemptStore.isLocked(USERNAME)).isFalse();
    }

    /** A success must clear the counter, so earlier typos cannot add up to a later lockout. */
    @Test
    void clearsAccumulatedFailuresAfterASuccessfulSignIn() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));
        when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));

        for (int attempt = 0; attempt < properties.getMaxAttempts() - 1; attempt++) {
            catchLoginException(() -> service.authenticate(USERNAME, "wrong"));
        }
        service.authenticate(USERNAME, PASSWORD);

        for (int attempt = 0; attempt < properties.getMaxAttempts() - 1; attempt++) {
            assertThat(catchLoginException(() -> service.authenticate(USERNAME, "wrong"))
                    .getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
        }
        assertThat(attemptStore.isLocked(USERNAME)).isFalse();
    }

    @Test
    void handlesANullPasswordAsAPlainFailureRatherThanThrowing() {
        stubFound(accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN));

        assertThat(catchLoginException(() -> service.authenticate(USERNAME, null)).getErrorCode())
                .isEqualTo("INVALID_CREDENTIALS");
    }

    private static PasswordLoginException catchLoginException(Runnable call) {
        try {
            call.run();
        } catch (PasswordLoginException ex) {
            return ex;
        }
        throw new AssertionError("expected a PasswordLoginException, but none was thrown");
    }

    // ----- Disabled accounts (Requirement 19.2) -----

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "DEACTIVATED"})
    void refusesTheCorrectPasswordForADisabledAccountWithoutIssuingTokens(String status) {
        UserAccount account = accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN);
        account.changeStatus(AccountStatus.valueOf(status));
        stubFound(account);

        assertThatThrownBy(() -> service.authenticate(USERNAME, PASSWORD))
                .isInstanceOf(AccountDisabledException.class)
                .satisfies(ex -> assertThat(((AccountDisabledException) ex).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }

    /** The status is revealed only to a caller who already knows the password. */
    @Test
    void aWrongPasswordForADisabledAccountIsThePlainCredentialFailure() {
        UserAccount account = accountWithCredentials(USERNAME, PASSWORD, Role.ADMIN);
        account.changeStatus(AccountStatus.SUSPENDED);
        stubFound(account);

        assertThatThrownBy(() -> service.authenticate(USERNAME, "wrong"))
                .isInstanceOf(PasswordLoginException.class)
                .satisfies(ex -> assertThat(((PasswordLoginException) ex).getErrorCode())
                        .isEqualTo("INVALID_CREDENTIALS"));
    }
}
