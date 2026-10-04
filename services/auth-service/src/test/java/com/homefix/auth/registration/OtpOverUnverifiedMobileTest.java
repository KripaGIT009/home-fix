package com.homefix.auth.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homefix.auth.config.OtpProperties;
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
 * OTP sign-in over a number given at email sign-up but never verified. The OTP proves the number, the
 * email sign-up did not, so the OTP holder must never be signed in to that account: they get their
 * own, and the number leaves the other one.
 */
@ExtendWith(MockitoExtension.class)
class OtpOverUnverifiedMobileTest {

    private static final String PHONE = "+919876543210";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private TokenService tokenService;

    private FakeSmsGateway sms;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        OtpProperties otp = new OtpProperties();
        otp.setTtl(Duration.ofMinutes(5));
        otp.setLength(6);
        otp.setMaxVerifyAttempts(5);
        otp.setLockout(Duration.ofMinutes(30));
        otp.setMaxRequestsPerWindow(5);
        otp.setRateLimitWindow(Duration.ofHours(1));
        sms = new FakeSmsGateway();
        service = new RegistrationService(otp, new InMemoryOtpStore(), new OtpCodeGenerator(), sms,
                userRepository, tokenService);
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));
    }

    private VerificationResult otpSignIn() {
        service.requestOtp(PHONE, "CUSTOMER");
        return service.verifyOtp(PHONE, sms.lastOtpCode());
    }

    @Test
    void anActiveEmailAccount_releasesTheNumber_andTheOtpHolderGetsTheirOwnAccount() {
        UserAccount emailAccount = UserAccount.createPendingEmailSignup("X", "x@example.com", PHONE,
                Role.CUSTOMER, "hash");
        emailAccount.completeEmailVerification(Instant.now());
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(emailAccount));

        VerificationResult result = otpSignIn();

        assertThat(result.userId()).isNotEqualTo(emailAccount.getId().toString());
        assertThat(emailAccount.getMobileNumber()).isNull();
        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        UserAccount created = saved.getAllValues().get(1);
        assertThat(created.getMobileNumber()).isEqualTo(PHONE);
        assertThat(created.isMobileVerified()).isTrue();
    }

    @Test
    void anUnverifiedSignupHoldingTheNumber_isRemoved() {
        UserAccount pending = UserAccount.createPendingEmailSignup("X", "x@example.com", PHONE,
                Role.CUSTOMER, "hash");
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(pending));

        VerificationResult result = otpSignIn();

        verify(userRepository).delete(pending);
        assertThat(result.userId()).isNotEqualTo(pending.getId().toString());
    }

    @Test
    void aVerifiedNumber_stillSignsInToItsAccount() {
        UserAccount owner = UserAccount.createVerified(PHONE, Role.CUSTOMER);
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(owner));

        assertThat(otpSignIn().userId()).isEqualTo(owner.getId().toString());
    }
}
