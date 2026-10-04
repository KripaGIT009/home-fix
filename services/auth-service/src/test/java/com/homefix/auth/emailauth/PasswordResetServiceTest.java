package com.homefix.auth.emailauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.support.InMemoryEmailCodeStore;
import com.homefix.auth.support.InMemoryLoginAttemptStore;
import com.homefix.auth.support.RecordingEmailSender;
import com.homefix.auth.token.TokenService;

/**
 * Tests for {@link PasswordResetService} (email-auth Requirement 3): the same answer for every
 * address (Property EA1), a reset that ends every session (EA5), and a weak password that does not
 * spend the code.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final String EMAIL = "asha@example.com";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private TokenService tokenService;

    private PasswordEncoder encoder;
    private RecordingEmailSender emails;
    private InMemoryLoginAttemptStore attempts;
    private PasswordResetService service;
    private UserAccount account;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        emails = new RecordingEmailSender();
        attempts = new InMemoryLoginAttemptStore();
        EmailCodes codes = new EmailCodes(new InMemoryEmailCodeStore(), new OtpCodeGenerator(),
                new EmailAuthProperties());
        service = new PasswordResetService(userRepository, encoder, codes, emails, tokenService, attempts);

        account = UserAccount.createVerified("+919811100001", Role.CUSTOMER);
        account.setVerifiedEmail(EMAIL, Instant.now());
        account.changePasswordHash(encoder.encode("oldpass123"));
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(account));
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void requestReset_emailsACodeOnlyToAnAccount_butAnswersTheSame() {
        long unknown = service.requestReset("nobody@example.com", null);
        assertThat(emails.sent()).isEmpty();

        long known = service.requestReset("ASHA@example.com", null);

        assertThat(known).isEqualTo(unknown);
        assertThat(emails.sent()).hasSize(1);
        assertThat(emails.last().to()).isEqualTo(EMAIL);
    }

    @Test
    void requestReset_answersTheSameWhenTheEmailCannotBeSent() {
        emails.failDelivery();

        assertThat(service.requestReset(EMAIL, null)).isEqualTo(600);
    }

    @Test
    void reset_setsTheNewPassword_endsEverySession_andLiftsTheLockout() {
        attempts.lock(EMAIL, Duration.ofMinutes(30));
        service.requestReset(EMAIL, null);

        service.reset(EMAIL, emails.lastCode(), "newpass2026");

        assertThat(encoder.matches("newpass2026", account.getPasswordHash())).isTrue();
        verify(tokenService).revokeAllRefreshTokens(account.getId().toString());
        assertThat(attempts.isLocked(EMAIL)).isFalse();
    }

    @Test
    void reset_withAWeakPassword_keepsTheCodeUsable() {
        service.requestReset(EMAIL, null);
        String code = emails.lastCode();

        assertThatThrownBy(() -> service.reset(EMAIL, code, "short")).extracting("errorCode")
                .isEqualTo("WEAK_PASSWORD");
        service.reset(EMAIL, code, "longer2026");
        assertThat(encoder.matches("longer2026", account.getPasswordHash())).isTrue();
    }

    @Test
    void reset_withAWrongCode_changesNothing() {
        service.requestReset(EMAIL, null);
        String wrong = emails.lastCode().equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> service.reset(EMAIL, wrong, "newpass2026")).extracting("errorCode")
                .isEqualTo("INVALID_CODE");
        assertThat(encoder.matches("oldpass123", account.getPasswordHash())).isTrue();
        verify(tokenService, never()).revokeAllRefreshTokens(anyString());
    }
}
