package com.homefix.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.auth.admin.AdminUserService;
import com.homefix.auth.config.AuthRbacConfig;
import com.homefix.auth.config.WebSecurityConfig;
import com.homefix.auth.domain.AccountStatus;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.auth.token.TokenService;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the Admin Portal User Management endpoints (Requirement 19.2) through the
 * service's real security chain ({@link WebSecurityConfig}, the shared JWT and RBAC filters and the
 * {@link AuthRbacConfig} rules) and the real {@link AdminUserService}, so the response shape the
 * portal's {@code AdminUser} type reads, the role gate and the guard rails' error envelope are
 * all exercised as deployed. Only the repository and the token service are mocked.
 */
@WebMvcTest(controllers = AdminUserController.class, properties = {
        "homefix.security.jwt-secret=" + AdminUserControllerTest.JWT_SECRET
})
@Import({WebSecurityConfig.class, AuthRbacConfig.class, AdminUserService.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class AdminUserControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";

    private final UUID callerId = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserAccountRepository userRepository;

    @MockBean
    private TokenService tokenService;

    private String bearer(UUID subject, String... roles) {
        String token = Jwts.builder()
                .subject(subject.toString())
                .claim("roles", List.of(roles))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        return "Bearer " + token;
    }

    private UserAccount staffAccount() {
        UserAccount account = UserAccount.createVerified("+919000000011", Role.SUPPORT_AGENT);
        account.setCredentials("ops.lead", "$2a$12$hash");
        return account;
    }

    private UserAccount stubFound(UserAccount account) {
        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        return account;
    }

    // ----- GET /admin/users -----

    @Test
    void list_returnsTheAdminUserShape() throws Exception {
        UserAccount staff = staffAccount();
        staff.addRole(Role.DISPATCHER);
        UserAccount social = UserAccount.createSocial(Role.CUSTOMER);
        social.changeStatus(AccountStatus.SUSPENDED);
        when(userRepository.findByOrderByCreatedAtDesc(any())).thenReturn(List.of(staff, social));

        mockMvc.perform(get("/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(staff.getId().toString()))
                .andExpect(jsonPath("$[0].displayName").value("ops.lead"))
                .andExpect(jsonPath("$[0].mobileNumber").value("+919000000011"))
                .andExpect(jsonPath("$[0].email").isEmpty())
                .andExpect(jsonPath("$[0].roles[0]").value("DISPATCHER"))
                .andExpect(jsonPath("$[0].roles[1]").value("SUPPORT_AGENT"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                // ISO-8601, as the portal's formatDate expects, not epoch seconds.
                .andExpect(jsonPath("$[0].createdAt").value(staff.getCreatedAt().toString()))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[1].displayName").isEmpty())
                .andExpect(jsonPath("$[1].mobileNumber").isEmpty())
                .andExpect(jsonPath("$[1].status").value("SUSPENDED"));
    }

    @Test
    void list_withSearch_usesTheEscapedSubstringPattern() throws Exception {
        when(userRepository.searchForAdmin(eq("%98765%"), any())).thenReturn(List.of());

        mockMvc.perform(get("/admin/users").param("search", "98765")
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_customerToken_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(callerId, "CUSTOMER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userRepository);
    }

    @Test
    void list_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userRepository);
    }

    // ----- PATCH /admin/users/{id}/status -----

    @Test
    void suspend_returnsTheUpdatedUserAndRevokesItsSessions() throws Exception {
        UserAccount target = stubFound(UserAccount.createVerified("+919000000012", Role.CUSTOMER));

        mockMvc.perform(patch("/admin/users/{id}/status", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        verify(tokenService).revokeAllRefreshTokens(target.getId().toString());
    }

    @Test
    void reactivate_returnsActive() throws Exception {
        UserAccount target = UserAccount.createVerified("+919000000013", Role.SERVICE_PROVIDER);
        target.changeStatus(AccountStatus.DEACTIVATED);
        stubFound(target);

        mockMvc.perform(patch("/admin/users/{id}/status", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void ownStatus_isForbiddenInTheSharedEnvelope() throws Exception {
        UserAccount self = stubFound(UserAccount.createVerified("+919000000014", Role.SUPER_ADMIN));

        mockMvc.perform(patch("/admin/users/{id}/status", self.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(self.getId(), "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SELF_STATUS_CHANGE_FORBIDDEN"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void adminChangingAnAdministrator_isForbidden() throws Exception {
        UserAccount otherAdmin = stubFound(UserAccount.createVerified("+919000000015", Role.ADMIN));

        mockMvc.perform(patch("/admin/users/{id}/status", otherAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SUPER_ADMIN_REQUIRED"));
    }

    @Test
    void superAdminChangingAnAdministrator_succeeds() throws Exception {
        UserAccount otherAdmin = stubFound(UserAccount.createVerified("+919000000016", Role.ADMIN));

        mockMvc.perform(patch("/admin/users/{id}/status", otherAdmin.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEACTIVATED"));
    }

    @Test
    void unknownUser_returns404InSharedEnvelope() throws Exception {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(patch("/admin/users/{id}/status", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }

    @Test
    void unknownStatusValue_returns400() throws Exception {
        mockMvc.perform(patch("/admin/users/{id}/status", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BANNED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verifyNoInteractions(userRepository);
    }

    @Test
    void missingStatus_returns400() throws Exception {
        mockMvc.perform(patch("/admin/users/{id}/status", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void customerToken_cannotChangeAStatus() throws Exception {
        mockMvc.perform(patch("/admin/users/{id}/status", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(callerId, "CUSTOMER", "SERVICE_PROVIDER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userRepository, tokenService);
    }
}
