package com.homefix.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.homefix.auth.admin.StaffActor;
import com.homefix.auth.domain.Role;
import com.homefix.auth.emailauth.AccountCredentialsService;
import com.homefix.auth.emailauth.AccountCredentialsService.Credentials;
import com.homefix.auth.emailauth.EmailAuthException;
import com.homefix.auth.emailauth.EmailSignupService;
import com.homefix.auth.emailauth.PasswordResetService;
import com.homefix.auth.invitation.StaffInvitationService;
import com.homefix.auth.invitation.StaffInvitationService.InvitationView;
import com.homefix.auth.password.PasswordLoginService;
import com.homefix.auth.password.PasswordLoginService.LoginResult;
import com.homefix.auth.token.TokenPair;

/**
 * Web-layer tests for the email-auth endpoints (standalone MockMvc, services mocked): statuses,
 * bodies, the shared error envelope with Retry-After, shape validation, the caller taken from the
 * token, and the legacy {@code username} field on password sign-in.
 */
class EmailAuthControllersTest {

    private EmailSignupService signupService;
    private PasswordResetService resetService;
    private AccountCredentialsService credentialsService;
    private StaffInvitationService invitationService;
    private PasswordLoginService passwordLoginService;
    private MockMvc mvc;

    private final LoginResult login = new LoginResult("u-1", List.of("CUSTOMER"),
            new TokenPair("access", "refresh", 900L));

    @BeforeEach
    void setUp() {
        signupService = mock(EmailSignupService.class);
        resetService = mock(PasswordResetService.class);
        credentialsService = mock(AccountCredentialsService.class);
        invitationService = mock(StaffInvitationService.class);
        passwordLoginService = mock(PasswordLoginService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new EmailSignupController(signupService),
                        new PasswordResetController(resetService),
                        new AccountCredentialsController(credentialsService),
                        new InvitationController(invitationService),
                        new PasswordLoginController(passwordLoginService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(UUID userId, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null,
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    @Test
    void signup_answers202CodeSent_withTheClientIpFromTheLastProxyHop() throws Exception {
        when(signupService.register(any(), eq("10.0.0.9"))).thenReturn(600L);

        mvc.perform(post("/auth/register/email").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.9")
                        .content("""
                                {"displayName":"Asha Rao","email":"asha@example.com","mobileNumber":"+919811100001",
                                 "password":"homefix2026","role":"CUSTOMER"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CODE_SENT"))
                .andExpect(jsonPath("$.expiresInSeconds").value(600));
    }

    @Test
    void signup_withABadEmailOrMobile_is400() throws Exception {
        mvc.perform(post("/auth/register/email").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Asha","email":"not-an-email","mobileNumber":"98111",
                                 "password":"homefix2026"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void verify_signsIn() throws Exception {
        when(signupService.verify("asha@example.com", "123456")).thenReturn(login);

        mvc.perform(post("/auth/register/email/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void aThrottledRequest_is429_withRetryAfter() throws Exception {
        when(resetService.requestReset(eq("asha@example.com"), any()))
                .thenThrow(EmailAuthException.tooManyRequests(42));

        mvc.perform(post("/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.errorCode").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void reset_is204() throws Exception {
        mvc.perform(post("/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"asha@example.com\",\"code\":\"123456\",\"newPassword\":\"next2026x\"}"))
                .andExpect(status().isNoContent());
        verify(resetService).reset("asha@example.com", "123456", "next2026x");
    }

    @Test
    void me_isTheTokensSubject() throws Exception {
        UUID userId = UUID.randomUUID();
        authenticate(userId, "CUSTOMER");
        when(credentialsService.credentials(userId)).thenReturn(new Credentials(userId, "Asha",
                "asha@example.com", true, "+919811100001", null, true, List.of("CUSTOMER")));

        mvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.hasPassword").value(true));

        mvc.perform(put("/auth/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"next2026x\"}"))
                .andExpect(status().isNoContent());
        verify(credentialsService).changePassword(eq(userId), isNull(), eq("next2026x"));
    }

    @Test
    void me_withoutAToken_is401() throws Exception {
        mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void invitations_areCreatedAsTheCallingAdmin() throws Exception {
        UUID adminId = UUID.randomUUID();
        authenticate(adminId, "SUPER_ADMIN", "ADMIN");
        when(invitationService.invite(any(StaffActor.class), eq("ravi@example.com"), eq("DISPATCHER")))
                .thenReturn(new InvitationView(UUID.randomUUID(), "ravi@example.com", Role.DISPATCHER, adminId,
                        "superadmin", Instant.now(), Instant.now()));

        mvc.perform(post("/admin/invitations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ravi@example.com\",\"role\":\"DISPATCHER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("DISPATCHER"));
        mvc.perform(delete("/admin/invitations/{id}", UUID.randomUUID())).andExpect(status().isNoContent());
    }

    @Test
    void anExpiredInvitationLink_is410() throws Exception {
        when(invitationService.preview("tok")).thenThrow(new EmailAuthException(
                org.springframework.http.HttpStatus.GONE, "INVITATION_EXPIRED", "gone"));

        mvc.perform(get("/auth/invitations/tok"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("INVITATION_EXPIRED"));
    }

    @Test
    void passwordSignIn_takesIdentifier_orTheOldUsernameField() throws Exception {
        when(passwordLoginService.authenticate("asha@example.com", "homefix2026")).thenReturn(login);
        when(passwordLoginService.authenticate("admin", "homefix2026")).thenReturn(login);

        mvc.perform(post("/auth/login/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"asha@example.com\",\"password\":\"homefix2026\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/auth/login/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"homefix2026\"}"))
                .andExpect(status().isOk());
    }
}
