package com.homefix.auth.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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

import com.homefix.auth.admin.StaffActor;
import com.homefix.auth.config.EmailAuthProperties;
import com.homefix.auth.config.PasswordLoginProperties;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.emailauth.EmailCodes;
import com.homefix.auth.invitation.StaffInvitationService.AcceptCommand;
import com.homefix.auth.otp.OtpCodeGenerator;
import com.homefix.auth.password.PasswordLoginService.LoginResult;
import com.homefix.auth.support.InMemoryEmailCodeStore;
import com.homefix.auth.support.InMemoryLoginAttemptStore;
import com.homefix.auth.support.RecordingEmailSender;
import com.homefix.auth.token.TokenPair;
import com.homefix.auth.token.TokenService;

/**
 * Tests for {@link StaffInvitationService} (email-auth Requirement 6): who may invite which role
 * (Property EA3), that only a hash of the link's token is kept, re-inviting, and acceptance by a new
 * or an existing account.
 */
@ExtendWith(MockitoExtension.class)
class StaffInvitationServiceTest {

    private static final String EMAIL = "ravi@example.com";
    private static final StaffActor SUPER = new StaffActor(UUID.randomUUID(), Set.of("SUPER_ADMIN", "ADMIN"));
    private static final StaffActor ADMIN = new StaffActor(UUID.randomUUID(), Set.of("ADMIN"));

    @Mock
    private StaffInvitationRepository invitations;
    @Mock
    private UserAccountRepository users;
    @Mock
    private TokenService tokenService;

    private PasswordEncoder encoder;
    private RecordingEmailSender emails;
    private StaffInvitationService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        emails = new RecordingEmailSender();
        PasswordLoginProperties passwordProperties = new PasswordLoginProperties();
        passwordProperties.setMaxAttempts(5);
        passwordProperties.setLockout(Duration.ofMinutes(30));
        passwordProperties.setFailureWindow(Duration.ofMinutes(15));
        EmailAuthProperties properties = new EmailAuthProperties();
        EmailCodes codes = new EmailCodes(new InMemoryEmailCodeStore(), new OtpCodeGenerator(), properties);
        service = new StaffInvitationService(invitations, users, encoder, emails, tokenService, codes,
                new InMemoryLoginAttemptStore(), passwordProperties, properties);

        lenient().when(invitations.save(any(StaffInvitation.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(invitations.findOpenByEmail(anyString())).thenReturn(List.of());
        lenient().when(users.findById(any())).thenReturn(Optional.empty());
        lenient().when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        lenient().when(users.findByMobileNumber(anyString())).thenReturn(Optional.empty());
        lenient().when(users.saveAndFlush(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(invitations.markAccepted(any(), any(), any())).thenReturn(1);
        lenient().when(tokenService.issueTokens(anyString(), anyList()))
                .thenReturn(new TokenPair("access", "refresh", 900L));
    }

    /** Invites, then makes the stored invitation findable by its hash, as the database would. */
    private StaffInvitation invite(StaffActor actor, String role) {
        service.invite(actor, EMAIL, role);
        ArgumentCaptor<StaffInvitation> saved = ArgumentCaptor.forClass(StaffInvitation.class);
        verify(invitations).save(saved.capture());
        StaffInvitation invitation = saved.getValue();
        lenient().when(invitations.findByTokenHash(invitation.getTokenHash())).thenReturn(Optional.of(invitation));
        return invitation;
    }

    @Test
    void aSuperAdmin_mayInviteAnAdmin_andTheLinkTokenIsStoredOnlyAsAHash() {
        StaffInvitation invitation = invite(SUPER, "ADMIN");

        String token = emails.lastInvitationToken();
        assertThat(emails.last().to()).isEqualTo(EMAIL);
        assertThat(emails.last().message().body()).contains("http://localhost:5175/invite/" + token);
        assertThat(invitation.getTokenHash()).isNotEqualTo(token).isEqualTo(StaffInvitationService.sha256(token));
        assertThat(invitation.getRole()).isEqualTo(Role.ADMIN);
        assertThat(invitation.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
    }

    @Test
    void anAdmin_mayInviteTheDeskRoles_butNotAnAdmin() {
        invite(ADMIN, "DISPATCHER");

        assertThatThrownBy(() -> service.invite(ADMIN, "other@example.com", "ADMIN"))
                .extracting("errorCode").isEqualTo("SUPER_ADMIN_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "TENANT_ADMIN", "CUSTOMER", "SERVICE_PROVIDER", "NOPE"})
    void noInvitation_canCarryAnyOtherRole_evenFromASuperAdmin(String role) {
        assertThatThrownBy(() -> service.invite(SUPER, EMAIL, role)).extracting("errorCode")
                .isEqualTo("INVALID_ROLE");
        verify(invitations, never()).save(any());
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    void reinviting_revokesTheOpenInvitation() {
        StaffInvitation open = new StaffInvitation(EMAIL, Role.DISPATCHER, "x".repeat(64), SUPER.userId(),
                Instant.now(), Instant.now().plus(Duration.ofDays(7)));
        when(invitations.findOpenByEmail(EMAIL)).thenReturn(List.of(open));

        invite(SUPER, "SUPPORT_AGENT");

        assertThat(open.getRevokedAt()).isNotNull();
    }

    @Test
    void anUnknownOrClosedLink_isGone() {
        StaffInvitation invitation = invite(SUPER, "FINANCE_ADMIN");
        String token = emails.lastInvitationToken();
        assertThat(service.preview(token).role()).isEqualTo(Role.FINANCE_ADMIN);

        assertThatThrownBy(() -> service.preview("not-a-real-token")).extracting("errorCode")
                .isEqualTo("INVITATION_EXPIRED");
        invitation.revoke(Instant.now());
        assertThatThrownBy(() -> service.preview(token)).extracting("errorCode").isEqualTo("INVITATION_EXPIRED");
    }

    @Test
    void accepting_withoutAnAccount_createsOneWithTheRole_andTheEmailVerified() {
        StaffInvitation invitation = invite(SUPER, "DISPATCHER");

        LoginResult result = service.accept(emails.lastInvitationToken(),
                new AcceptCommand("Ravi Kumar", "+919811100007", "dispatch2026"), null);

        ArgumentCaptor<UserAccount> created = ArgumentCaptor.forClass(UserAccount.class);
        verify(users, org.mockito.Mockito.atLeastOnce()).saveAndFlush(created.capture());
        UserAccount account = created.getValue();
        assertThat(account.getRoles()).containsExactly(Role.DISPATCHER);
        assertThat(account.getEmail()).isEqualTo(EMAIL);
        assertThat(account.isEmailVerified()).isTrue();
        assertThat(account.isMobileVerified()).isFalse();
        assertThat(encoder.matches("dispatch2026", account.getPasswordHash())).isTrue();
        assertThat(result.roles()).containsExactly("DISPATCHER");
        verify(invitations).markAccepted(eq(invitation.getId()), eq(account.getId()), any());
    }

    @Test
    void accepting_withAnExistingAccount_needsItsPassword_andAddsTheRole() {
        invite(SUPER, "SUPPORT_AGENT");
        UserAccount existing = UserAccount.createVerified("+919811100008", Role.CUSTOMER);
        existing.setVerifiedEmail(EMAIL, Instant.now());
        existing.changePasswordHash(encoder.encode("mine2026"));
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        String token = emails.lastInvitationToken();

        assertThatThrownBy(() -> service.accept(token, new AcceptCommand(null, null, "wrong2026"), null))
                .extracting("errorCode").isEqualTo("INVALID_CREDENTIALS");
        assertThat(existing.getRoles()).containsExactly(Role.CUSTOMER);

        service.accept(token, new AcceptCommand(null, null, "mine2026"), null);
        assertThat(existing.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.SUPPORT_AGENT);
    }

    @Test
    void accepting_aLinkSomeoneElseJustUsed_isGone() {
        invite(SUPER, "DISPATCHER");
        when(invitations.markAccepted(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.accept(emails.lastInvitationToken(),
                new AcceptCommand("Ravi Kumar", "+919811100007", "dispatch2026"), null))
                .extracting("errorCode").isEqualTo("INVITATION_EXPIRED");
        verify(tokenService, never()).issueTokens(anyString(), anyList());
    }
}
