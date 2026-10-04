package com.homefix.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.support.InMemoryLoginAttemptStore;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Email sign-in through {@link PasswordLoginService} (email-auth Requirement 2): case-insensitive
 * email, lockout per address, and {@code EMAIL_NOT_VERIFIED} only once the password is right.
 */
@ExtendWith(MockitoExtension.class)
class PasswordLoginByEmailTest {

    private static final String EMAIL = "asha@example.com";
    private static final String PASSWORD = "homefix2026";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private TokenService tokenService;

    private PasswordEncoder encoder;
    private InMemoryLoginAttemptStore attempts;
    private PasswordLoginService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        attempts = new InMemoryLoginAttemptStore();
        PasswordLoginProperties properties = new PasswordLoginProperties();
        properties.setMaxAttempts(5);
        properties.setLockout(Duration.ofMinutes(30));
        properties.setFailureWindow(Duration.ofMinutes(15));
        service = new PasswordLoginService(userRepository, encoder, tokenService, attempts, properties);
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));
    }

    @Test
    void anEmailAccount_signsInWithItsAddressInAnyCase() {
        UserAccount account = UserAccount.createVerified("+919811100001", Role.SERVICE_PROVIDER);
        account.setVerifiedEmail(EMAIL, Instant.now());
        account.changePasswordHash(encoder.encode(PASSWORD));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));

        PasswordLoginService.LoginResult result = service.authenticate("  Asha@Example.com ", PASSWORD);

        assertThat(result.userId()).isEqualTo(account.getId().toString());
        assertThat(result.roles()).containsExactly("SERVICE_PROVIDER");
        verify(userRepository, never()).findByUsername(anyString());
    }

    @Test
    void anUnverifiedSignup_isToldToVerify_onlyWhenThePasswordIsRight() {
        UserAccount pending = UserAccount.createPendingEmailSignup("Asha", EMAIL, "+919811100001",
                Role.CUSTOMER, encoder.encode(PASSWORD));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.authenticate(EMAIL, "wrong-pass1"))
                .extracting("errorCode").isEqualTo("INVALID_CREDENTIALS");
        assertThatThrownBy(() -> service.authenticate(EMAIL, PASSWORD))
                .extracting("errorCode").isEqualTo("EMAIL_NOT_VERIFIED");
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }

    @Test
    void anUnknownEmail_isTheSameFailure_andCountsTowardsTheLockout() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.authenticate("nobody@example.com", PASSWORD))
                    .extracting("errorCode").isEqualTo("INVALID_CREDENTIALS");
        }
        assertThatThrownBy(() -> service.authenticate("NOBODY@example.com", PASSWORD))
                .extracting("errorCode").isEqualTo("ACCOUNT_LOCKED");
        assertThat(attempts.isLocked("nobody@example.com")).isTrue();
    }
}
