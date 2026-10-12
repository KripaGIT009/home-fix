package com.homefix.auth.emailauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.support.InMemoryEmailCodeStore;
import com.homefix.auth.support.InMemoryLoginAttemptStore;
import com.homefix.auth.support.RecordingEmailSender;

/**
 * Tests for {@link AccountCredentialsService} (email-auth Requirement 4): an email is saved only once
 * its code is entered, is unique among real accounts, and changing email or password needs the
 * current password when there is one.
 */
@ExtendWith(MockitoExtension.class)
class AccountCredentialsServiceTest {

    private static final String NEW_EMAIL = "asha@example.com";

    @Mock
    private UserAccountRepository userRepository;

    private PasswordEncoder encoder;
    private RecordingEmailSender emails;
    private AccountCredentialsService service;
    private UserAccount otpAccount;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        emails = new RecordingEmailSender();
        PasswordLoginProperties passwordProperties = new PasswordLoginProperties();
        passwordProperties.setMaxAttempts(5);
        passwordProperties.setLockout(Duration.ofMinutes(30));
        passwordProperties.setFailureWindow(Duration.ofMinutes(15));
        EmailCodes codes = new EmailCodes(new InMemoryEmailCodeStore(), new OtpCodeGenerator(),
                new EmailAuthProperties());
        service = new AccountCredentialsService(userRepository, encoder, codes, emails,
                new InMemoryLoginAttemptStore(), passwordProperties);

        otpAccount = UserAccount.createVerified("+919811100001", Role.CUSTOMER);
        lenient().when(userRepository.findById(otpAccount.getId())).thenReturn(Optional.of(otpAccount));
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.saveAndFlush(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void anOtpAccount_addsAnEmail_onlyOnceTheCodeIsEntered() {
        service.requestEmailChange(otpAccount.getId(), "Asha@Example.com", null);

        assertThat(otpAccount.getEmail()).isNull();
        assertThat(emails.last().to()).isEqualTo(NEW_EMAIL);

        service.confirmEmailChange(otpAccount.getId(), emails.lastCode());

        assertThat(otpAccount.getEmail()).isEqualTo(NEW_EMAIL);
        assertThat(otpAccount.isEmailVerified()).isTrue();
    }

    @Test
    void anEmailAnotherAccountUses_isRefused() {
        UserAccount other = UserAccount.createVerified("+919811100002", Role.CUSTOMER);
        other.setVerifiedEmail(NEW_EMAIL, Instant.now());
        when(userRepository.findByEmail(NEW_EMAIL)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.requestEmailChange(otpAccount.getId(), NEW_EMAIL, null))
                .extracting("errorCode").isEqualTo("EMAIL_IN_USE");
    }

    @Test
    void anEmailHeldOnlyByAnUnverifiedSignup_goesToThePersonWhoProvesIt() {
        UserAccount squatter = UserAccount.createPendingEmailSignup("X", NEW_EMAIL, "+919811100003",
                Role.CUSTOMER, "h");
        when(userRepository.findByEmail(NEW_EMAIL)).thenReturn(Optional.of(squatter));

        service.requestEmailChange(otpAccount.getId(), NEW_EMAIL, null);

        verify(userRepository).delete(squatter);
    }

    @Test
    void withAPassword_changingEmailOrPassword_needsTheCurrentOne() {
        otpAccount.setVerifiedEmail("old@example.com", Instant.now());
        otpAccount.changePasswordHash(encoder.encode("current2026"));

        assertThatThrownBy(() -> service.requestEmailChange(otpAccount.getId(), NEW_EMAIL, "wrong"))
                .extracting("errorCode").isEqualTo("CURRENT_PASSWORD_INCORRECT");
        assertThatThrownBy(() -> service.changePassword(otpAccount.getId(), null, "next2026x"))
                .extracting("errorCode").isEqualTo("CURRENT_PASSWORD_INCORRECT");

        service.changePassword(otpAccount.getId(), "current2026", "next2026x");
        assertThat(encoder.matches("next2026x", otpAccount.getPasswordHash())).isTrue();
    }

    @Test
    void aPassword_needsAnEmailToSignInWith() {
        assertThatThrownBy(() -> service.changePassword(otpAccount.getId(), null, "first2026"))
                .extracting("errorCode").isEqualTo("EMAIL_REQUIRED");

        otpAccount.setVerifiedEmail(NEW_EMAIL, Instant.now());
        service.changePassword(otpAccount.getId(), null, "first2026");

        assertThat(otpAccount.hasPassword()).isTrue();
        assertThat(otpAccount.hasPasswordCredentials()).isTrue();
    }

    @Test
    void credentials_hideAnUnverifiedEmail() {
        UserAccount pending = UserAccount.createPendingEmailSignup("Asha", NEW_EMAIL, "+919811100004",
                Role.CUSTOMER, "h");
        when(userRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThat(service.credentials(pending.getId()).email()).isNull();
        assertThat(service.credentials(otpAccount.getId()).roles()).containsExactly("CUSTOMER");
    }
}
