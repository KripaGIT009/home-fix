package com.homefix.auth.emailauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.emailauth.EmailSignupService.SignupCommand;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.password.PasswordLoginService.LoginResult;
import com.homefix.auth.support.InMemoryEmailCodeStore;
import com.homefix.auth.support.RecordingEmailSender;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Tests for {@link EmailSignupService} (email-auth Requirement 1): pending accounts and their codes,
 * the same answer for known and unknown addresses (Property EA1), self-service roles only (EA3), the
 * mobile conflict, stale sign-ups, and verification signing the person in.
 */
@ExtendWith(MockitoExtension.class)
class EmailSignupServiceTest {

    private static final String EMAIL = "asha@example.com";
    private static final String MOBILE = "+919811100001";
    private static final String PASSWORD = "homefix2026";

    @Mock
    private UserAccountRepository userRepository;
    @Mock
    private TokenService tokenService;

    private PasswordEncoder encoder;
    private RecordingEmailSender emails;
    private EmailSignupService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        emails = new RecordingEmailSender();
        EmailAuthProperties properties = new EmailAuthProperties();
        EmailCodes codes = new EmailCodes(new InMemoryEmailCodeStore(), new OtpCodeGenerator(), properties);
        service = new EmailSignupService(userRepository, encoder, codes, emails, tokenService, properties);
        lenient().when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.findByMobileNumber(anyString())).thenReturn(Optional.empty());
        lenient().when(userRepository.saveAndFlush(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));
    }

    private static SignupCommand signup(String email, String role) {
        return new SignupCommand("Asha Rao", email, MOBILE, PASSWORD, role);
    }

    private UserAccount savedAccount() {
        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(userRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void register_createsAPendingAccount_andEmailsACode() {
        long ttl = service.register(signup("  Asha@Example.COM ", "SERVICE_PROVIDER"), "203.0.113.7");

        assertThat(ttl).isEqualTo(600);
        UserAccount account = savedAccount();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(account.getEmail()).isEqualTo(EMAIL);
        assertThat(account.isEmailVerified()).isFalse();
        assertThat(account.isMobileVerified()).isFalse();
        assertThat(account.getRoles()).containsExactly(Role.SERVICE_PROVIDER);
        assertThat(account.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(emails.last().to()).isEqualTo(EMAIL);
        assertThat(emails.lastCode()).hasSize(6);
    }

    @Test
    void register_withoutARole_isACustomer() {
        service.register(signup(EMAIL, null), null);

        assertThat(savedAccount().getRoles()).containsExactly(Role.CUSTOMER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "DISPATCHER", "SUPPORT_AGENT",
            "TENANT_ADMIN", "ROOT"})
    void register_refusesEveryRoleButCustomerAndProvider(String role) {
        assertThatThrownBy(() -> service.register(signup(EMAIL, role), null))
                .extracting("errorCode").isEqualTo("INVALID_ROLE");
        verify(userRepository, never()).saveAndFlush(any());
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void register_refusesAWeakPassword() {
        assertThatThrownBy(() -> service.register(
                new SignupCommand("Asha", EMAIL, MOBILE, "password", "CUSTOMER"), null))
                .extracting("errorCode").isEqualTo("WEAK_PASSWORD");
    }

    @Test
    void register_withAnAddressThatHasAnAccount_answersTheSame_andChangesNothing() {
        long newAddressAnswer = service.register(signup("new@example.com", "CUSTOMER"), null);

        UserAccount existing = UserAccount.createVerified("+919811199999", Role.CUSTOMER);
        existing.setVerifiedEmail(EMAIL, Instant.now());
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        long knownAddressAnswer = service.register(signup(EMAIL, "CUSTOMER"), null);

        assertThat(knownAddressAnswer).isEqualTo(newAddressAnswer);
        verify(userRepository, org.mockito.Mockito.times(1)).saveAndFlush(any());
        assertThat(emails.last().to()).isEqualTo(EMAIL);
        assertThat(emails.last().message().subject()).isEqualTo("You already have a HomeFix account");
        assertThat(emails.last().message().body()).doesNotContainPattern("\\d{6}");
    }

    @Test
    void register_withAMobileAnotherAccountHolds_is409() {
        when(userRepository.findByMobileNumber(MOBILE))
                .thenReturn(Optional.of(UserAccount.createVerified(MOBILE, Role.CUSTOMER)));

        assertThatThrownBy(() -> service.register(signup(EMAIL, "CUSTOMER"), null))
                .extracting("errorCode").isEqualTo("MOBILE_IN_USE");
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void register_freesAMobileHeldOnlyByAStaleSignup() throws Exception {
        UserAccount stale = UserAccount.createPendingEmailSignup("Old", "old@example.com", MOBILE, Role.CUSTOMER, "h");
        backdate(stale, Duration.ofHours(25));
        when(userRepository.findByMobileNumber(MOBILE)).thenReturn(Optional.of(stale));

        service.register(signup(EMAIL, "CUSTOMER"), null);

        verify(userRepository).delete(stale);
        assertThat(savedAccount().getMobileNumber()).isEqualTo(MOBILE);
    }

    @Test
    void register_again_beforeVerifying_replacesThePendingSignup() {
        UserAccount pending = UserAccount.createPendingEmailSignup("Asha", EMAIL, "+919811100009", Role.CUSTOMER, "h");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));

        service.register(signup(EMAIL, "SERVICE_PROVIDER"), null);

        UserAccount saved = savedAccount();
        assertThat(saved.getId()).isEqualTo(pending.getId());
        assertThat(saved.getMobileNumber()).isEqualTo(MOBILE);
        assertThat(saved.getRoles()).containsExactly(Role.SERVICE_PROVIDER);
    }

    @Test
    void verify_withTheEmailedCode_activatesTheAccount_andSignsIn() {
        service.register(signup(EMAIL, "CUSTOMER"), null);
        UserAccount pending = savedAccount();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));

        LoginResult result = service.verify(EMAIL, emails.lastCode());

        assertThat(pending.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(pending.isEmailVerified()).isTrue();
        assertThat(result.userId()).isEqualTo(pending.getId().toString());
        assertThat(result.roles()).containsExactly("CUSTOMER");
        assertThat(result.tokens().accessToken()).isEqualTo("access");
    }

    @Test
    void verify_withAWrongCode_signsNobodyIn() {
        service.register(signup(EMAIL, "CUSTOMER"), null);
        String wrong = emails.lastCode().equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> service.verify(EMAIL, wrong)).extracting("errorCode").isEqualTo("INVALID_CODE");
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }

    @Test
    void resend_sendsACodeOnlyForAWaitingSignup_butAnswersTheSame() {
        long unknownAnswer = service.resend("nobody@example.com", null);
        assertThat(emails.sent()).isEmpty();

        UserAccount pending = UserAccount.createPendingEmailSignup("Asha", EMAIL, MOBILE, Role.CUSTOMER, "h");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        long pendingAnswer = service.resend(EMAIL, null);

        assertThat(pendingAnswer).isEqualTo(unknownAnswer);
        assertThat(emails.sent()).hasSize(1);
        assertThat(emails.lastCode()).hasSize(6);
    }

    @Test
    void register_whenTheEmailCannotBeSent_is502() {
        emails.failDelivery();

        assertThatThrownBy(() -> service.register(signup(EMAIL, "CUSTOMER"), null))
                .extracting("errorCode").isEqualTo("EMAIL_DELIVERY_FAILED");
    }

    static void backdate(UserAccount account, Duration age) throws Exception {
        Field createdAt = UserAccount.class.getDeclaredField("createdAt");
        createdAt.setAccessible(true);
        createdAt.set(account, Instant.now().minus(age));
    }
}
