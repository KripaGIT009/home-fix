package com.homefix.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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

import com.homefix.auth.config.OtpProperties;
import com.homefix.auth.domain.AccountDisabledException;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.registration.RegistrationService.VerificationResult;
import com.homefix.auth.support.FakeSmsGateway;
import com.homefix.auth.support.InMemoryOtpStore;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Unit tests for the OTP registration flow (Requirement 1, Property 25).
 *
 * <p>Uses in-memory fakes for the OTP store and SMS gateway plus Mockito for the repository
 * and token service — no Redis, DB, or Spring context required.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String PHONE = "+919876543210";

    private OtpProperties otpProperties;
    private InMemoryOtpStore otpStore;
    private OtpCodeGenerator codeGenerator;
    private FakeSmsGateway smsGateway;

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private TokenService tokenService;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        otpProperties = new OtpProperties();
        otpProperties.setTtl(Duration.ofMinutes(5));
        otpProperties.setLength(6);
        otpProperties.setMaxVerifyAttempts(5);
        otpProperties.setLockout(Duration.ofMinutes(30));
        otpProperties.setMaxRequestsPerWindow(5);
        otpProperties.setRateLimitWindow(Duration.ofHours(1));

        otpStore = new InMemoryOtpStore();
        codeGenerator = new OtpCodeGenerator();
        smsGateway = new FakeSmsGateway();

        service = new RegistrationService(otpProperties, otpStore, codeGenerator,
                smsGateway, userRepository, tokenService);
    }

    private void stubUserCreation() {
        lenient().when(userRepository.findByMobileNumber(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.save(any(UserAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access-jwt", "refresh-token", 900L));
    }

    // ----- Happy path (Requirement 1.1, 1.2) -----

    @Test
    void otpHappyPath_createsVerifiedAccountAndReturnsTokens() {
        stubUserCreation();

        long expiresIn = service.requestOtp(PHONE, "CUSTOMER");
        assertThat(expiresIn).isEqualTo(300L);
        assertThat(smsGateway.sentMessages()).hasSize(1);

        String code = smsGateway.lastOtpCode();
        VerificationResult result = service.verifyOtp(PHONE, code);

        assertThat(result.roles()).containsExactly("CUSTOMER");
        assertThat(result.tokens().accessToken()).isEqualTo("access-jwt");
        assertThat(result.tokens().refreshToken()).isEqualTo("refresh-token");
        verify(userRepository).save(any(UserAccount.class));
        // Session consumed on success.
        assertThat(otpStore.findSession(PHONE)).isEmpty();
    }

    // ----- Expired OTP (Requirement 1.4) -----

    @Test
    void expiredOtp_isRejected() {
        service.requestOtp(PHONE, "CUSTOMER");
        String code = smsGateway.lastOtpCode();

        // Advance past the 5-minute TTL.
        otpStore.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThatThrownBy(() -> service.verifyOtp(PHONE, code))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> {
                    RegistrationException re = (RegistrationException) ex;
                    assertThat(re.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(re.getErrorCode()).isEqualTo("OTP_EXPIRED_OR_MISSING");
                });
    }

    // ----- Incorrect OTP lockout: 5th wrong attempt locks for exactly 30 minutes -----
    // (Requirement 1.3, Property 25)

    @Test
    void fifthConsecutiveWrongOtp_locksSessionForThirtyMinutes() {
        service.requestOtp(PHONE, "CUSTOMER");

        // Attempts 1-4: incorrect, not yet locked.
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.verifyOtp(PHONE, "000000"))
                    .isInstanceOf(RegistrationException.class)
                    .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                            .isEqualTo("OTP_INCORRECT"));
        }
        assertThat(otpStore.isLocked(PHONE)).isFalse();

        // 5th incorrect attempt triggers the lockout.
        assertThatThrownBy(() -> service.verifyOtp(PHONE, "000000"))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> {
                    RegistrationException re = (RegistrationException) ex;
                    assertThat(re.getErrorCode()).isEqualTo("OTP_SESSION_LOCKED");
                    assertThat(re.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(re.getRetryAfterSeconds()).isEqualTo(1800L);
                });

        assertThat(otpStore.isLocked(PHONE)).isTrue();
        assertThat(otpStore.lockRemaining(PHONE)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void lockoutExpiresAfterExactlyThirtyMinutes() {
        service.requestOtp(PHONE, "CUSTOMER");
        for (int i = 1; i <= 5; i++) {
            try {
                service.verifyOtp(PHONE, "000000");
            } catch (RegistrationException ignored) {
                // expected
            }
        }
        assertThat(otpStore.isLocked(PHONE)).isTrue();

        // Just before 30 minutes: still locked.
        otpStore.advance(Duration.ofMinutes(29).plusSeconds(59));
        assertThat(otpStore.isLocked(PHONE)).isTrue();

        // At 30 minutes: unlocked.
        otpStore.advance(Duration.ofSeconds(1));
        assertThat(otpStore.isLocked(PHONE)).isFalse();
    }

    @Test
    void verifyWhileLocked_isRejectedWithLockout() {
        otpStore.lock(PHONE, Duration.ofMinutes(30));
        assertThatThrownBy(() -> service.verifyOtp(PHONE, "123456"))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("OTP_SESSION_LOCKED"));
    }

    // ----- SMS delivery failure: no pending session created (Requirement 1.16) -----

    @Test
    void smsDeliveryFailure_returnsErrorAndCreatesNoSession() {
        smsGateway.failDelivery();

        assertThatThrownBy(() -> service.requestOtp(PHONE, "CUSTOMER"))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> {
                    RegistrationException re = (RegistrationException) ex;
                    assertThat(re.getErrorCode()).isEqualTo("SMS_DELIVERY_FAILED");
                    assertThat(re.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                });

        assertThat(otpStore.findSession(PHONE)).isEmpty();
        assertThat(smsGateway.sentMessages()).isEmpty();
    }

    // ----- Per-phone rate limiting: max 5 requests/hour (Requirement 23.4) -----

    @Test
    void sixthOtpRequestWithinWindow_isRateLimited() {
        for (int i = 1; i <= 5; i++) {
            service.requestOtp(PHONE, "CUSTOMER");
        }
        assertThatThrownBy(() -> service.requestOtp(PHONE, "CUSTOMER"))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> {
                    RegistrationException re = (RegistrationException) ex;
                    assertThat(re.getErrorCode()).isEqualTo("OTP_RATE_LIMIT_EXCEEDED");
                    assertThat(re.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                });
    }

    @Test
    void rateLimitResetsAfterWindow() {
        for (int i = 1; i <= 5; i++) {
            service.requestOtp(PHONE, "CUSTOMER");
        }
        otpStore.advance(Duration.ofHours(1).plusSeconds(1));
        // Should succeed again after the window rolls over.
        long expiresIn = service.requestOtp(PHONE, "CUSTOMER");
        assertThat(expiresIn).isEqualTo(300L);
    }

    @Test
    void requestOtpWhileLocked_isRejected() {
        otpStore.lock(PHONE, Duration.ofMinutes(30));
        assertThatThrownBy(() -> service.requestOtp(PHONE, "CUSTOMER"))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("OTP_SESSION_LOCKED"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void providerRegistration_grantsServiceProviderRole() {
        stubUserCreation();
        service.requestOtp(PHONE, "SERVICE_PROVIDER");
        VerificationResult result = service.verifyOtp(PHONE, smsGateway.lastOtpCode());
        assertThat(result.roles()).containsExactly("SERVICE_PROVIDER");
    }

    @Test
    void invalidRole_isRejected() {
        assertThatThrownBy(() -> service.requestOtp(PHONE, "WIZARD"))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> assertThat(((RegistrationException) ex).getErrorCode())
                        .isEqualTo("INVALID_ROLE"));
    }

    @Test
    void existingUser_getsAdditionalRoleOnVerify() {
        UserAccount existing = UserAccount.createVerified(PHONE, Role.CUSTOMER);
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("a", "r", 900L));

        service.requestOtp(PHONE, "SERVICE_PROVIDER");
        VerificationResult result = service.verifyOtp(PHONE, smsGateway.lastOtpCode());

        assertThat(result.roles()).containsExactlyInAnyOrder("CUSTOMER", "SERVICE_PROVIDER");
    }

    // ----- Privilege escalation (staff roles are not self-assignable) -----

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "DISPATCHER", "SUPPORT_AGENT",
            "TENANT_ADMIN", "tenant_admin",
            "admin", " Admin ", "sUpEr_AdMiN"})
    void staffRole_cannotBeSelfAssignedAtRegistration(String requestedRole) {
        assertThatThrownBy(() -> service.requestOtp(PHONE, requestedRole))
                .isInstanceOf(RegistrationException.class)
                .satisfies(ex -> {
                    RegistrationException re = (RegistrationException) ex;
                    assertThat(re.getErrorCode()).isEqualTo("INVALID_ROLE");
                    assertThat(re.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });

        // No OTP was sent and no pending session was created, so the refusal is total.
        assertThat(smsGateway.sentMessages()).isEmpty();
        assertThat(otpStore.findSession(PHONE)).isEmpty();
        verify(userRepository, never()).save(any(UserAccount.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "customer", " service_provider "})
    void selfServiceRoles_areStillAccepted(String requestedRole) {
        stubUserCreation();

        long expiresIn = service.requestOtp(PHONE, requestedRole);

        assertThat(expiresIn).isEqualTo(300L);
        assertThat(smsGateway.sentMessages()).hasSize(1);
    }

    @Test
    void roleEnum_marksOnlyCustomerAndProviderSelfAssignable() {
        assertThat(Role.CUSTOMER.isSelfAssignable()).isTrue();
        assertThat(Role.SERVICE_PROVIDER.isSelfAssignable()).isTrue();
        assertThat(Role.ADMIN.isSelfAssignable()).isFalse();
        assertThat(Role.SUPER_ADMIN.isSelfAssignable()).isFalse();
        assertThat(Role.FINANCE_ADMIN.isSelfAssignable()).isFalse();
        assertThat(Role.DISPATCHER.isSelfAssignable()).isFalse();
        assertThat(Role.SUPPORT_AGENT.isSelfAssignable()).isFalse();
        assertThat(Role.TENANT_ADMIN.isSelfAssignable()).isFalse();
    }

    // ----- Disabled accounts (Requirement 19.2) -----

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "DEACTIVATED"})
    void disabledAccount_correctOtpIsRefusedWithoutTokensOrNewRole(String status) {
        UserAccount existing = UserAccount.createVerified(PHONE, Role.CUSTOMER);
        existing.changeStatus(AccountStatus.valueOf(status));
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(existing));

        service.requestOtp(PHONE, "SERVICE_PROVIDER");
        String code = smsGateway.lastOtpCode();

        assertThatThrownBy(() -> service.verifyOtp(PHONE, code))
                .isInstanceOf(AccountDisabledException.class)
                .satisfies(ex -> {
                    AccountDisabledException ade = (AccountDisabledException) ex;
                    assertThat(ade.getErrorCode()).isEqualTo("ACCOUNT_DISABLED");
                    assertThat(ade.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });

        assertThat(existing.getRoles()).containsExactly(Role.CUSTOMER);
        verify(userRepository, never()).save(any(UserAccount.class));
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }
}
