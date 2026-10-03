package com.homefix.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.auth.admin.AccountRoleService;
import com.homefix.auth.config.AuthRbacConfig;
import com.homefix.auth.config.InternalApiKeyFilter;
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
 * Web-layer tests for the internal account lookup and TENANT_ADMIN grant / revoke endpoints
 * (Requirement MT-2.2, MT-2.4) through the service's real security chain ({@link WebSecurityConfig},
 * the shared JWT and RBAC filters with the {@link AuthRbacConfig} rules) and the real
 * {@link AccountRoleService}: the response shape provider-service reads, the error envelope, and
 * the service-credential guard (no key, a wrong key, an end-user token) are exercised as deployed.
 * Only the repository and the token service are mocked.
 */
@WebMvcTest(controllers = InternalUserRoleController.class, properties = {
        "homefix.security.jwt-secret=" + InternalUserRoleControllerTest.JWT_SECRET,
        "homefix.auth.internal-api-key=" + InternalUserRoleControllerTest.INTERNAL_KEY
})
@Import({WebSecurityConfig.class, AuthRbacConfig.class, AccountRoleService.class})
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalUserRoleControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";
    private static final String PHONE = "+919000000031";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserAccountRepository userRepository;

    @MockBean
    private TokenService tokenService;

    private UserAccount stubAccount(Role role) {
        UserAccount account = UserAccount.createVerified(PHONE, role);
        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.save(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.saveAndFlush(any(UserAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        return account;
    }

    // ----- by-mobile -----

    @Test
    void byMobile_returnsIdRolesAndStatus() throws Exception {
        UserAccount account = UserAccount.createVerified(PHONE, Role.SERVICE_PROVIDER);
        account.addRole(Role.CUSTOMER);
        account.changeStatus(AccountStatus.SUSPENDED);
        when(userRepository.findByMobileNumber(PHONE)).thenReturn(Optional.of(account));

        mockMvc.perform(get("/internal/users/by-mobile").param("mobileNumber", PHONE)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(account.getId().toString()))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.roles[1]").value("SERVICE_PROVIDER"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.mobileNumber").doesNotExist());
    }

    @Test
    void byMobile_unknownNumber_is404UserNotFound() throws Exception {
        when(userRepository.findByMobileNumber(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/users/by-mobile").param("mobileNumber", "+919999999999")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }

    @Test
    void byMobile_missingNumber_is400() throws Exception {
        mockMvc.perform(get("/internal/users/by-mobile").header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ----- grant / revoke -----

    @Test
    void grant_returnsTheAccountWithTheRole() throws Exception {
        UserAccount account = stubAccount(Role.CUSTOMER);

        mockMvc.perform(post("/internal/users/{id}/roles/TENANT_ADMIN", account.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(account.getId().toString()))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.roles[1]").value("TENANT_ADMIN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(userRepository).saveAndFlush(account);
    }

    @Test
    void grant_twice_isStill200() throws Exception {
        UserAccount account = stubAccount(Role.TENANT_ADMIN);

        mockMvc.perform(post("/internal/users/{id}/roles/TENANT_ADMIN", account.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("TENANT_ADMIN"));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void revoke_removesTheRoleAndEndsSessions() throws Exception {
        UserAccount account = stubAccount(Role.CUSTOMER);
        account.addRole(Role.TENANT_ADMIN);

        mockMvc.perform(delete("/internal/users/{id}/roles/TENANT_ADMIN", account.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));

        verify(tokenService).revokeAllRefreshTokens(account.getId().toString());
    }

    @Test
    void revoke_whenNotHeld_isStill200() throws Exception {
        UserAccount account = stubAccount(Role.CUSTOMER);

        mockMvc.perform(delete("/internal/users/{id}/roles/TENANT_ADMIN", account.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "SERVICE_PROVIDER", "tenant_admin", "NOT_A_ROLE"})
    void otherRoles_are400OnGrantAndRevoke(String role) throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/internal/users/{id}/roles/{role}", userId, role)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ROLE_NOT_MANAGEABLE"));
        mockMvc.perform(delete("/internal/users/{id}/roles/{role}", userId, role)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ROLE_NOT_MANAGEABLE"));

        verifyNoInteractions(userRepository, tokenService);
    }

    @Test
    void unknownUser_is404OnGrantAndRevoke() throws Exception {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(post("/internal/users/{id}/roles/TENANT_ADMIN", UUID.randomUUID())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
        mockMvc.perform(delete("/internal/users/{id}/roles/TENANT_ADMIN", UUID.randomUUID())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }

    @Test
    void malformedUserId_is400() throws Exception {
        mockMvc.perform(post("/internal/users/not-a-uuid/roles/TENANT_ADMIN")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ----- service credential -----

    @Test
    void missingOrWrongServiceCredential_is401AndTouchesNothing() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(get("/internal/users/by-mobile").param("mobileNumber", PHONE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));
        mockMvc.perform(post("/internal/users/{id}/roles/TENANT_ADMIN", userId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/internal/users/{id}/roles/TENANT_ADMIN", userId)
                        .header(InternalApiKeyFilter.HEADER, "not-the-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verifyNoInteractions(userRepository, tokenService);
    }

    @Test
    void endUserTokenWithoutServiceCredential_isRefused() throws Exception {
        // Even a SUPER_ADMIN cannot use the internal surface to make someone a Tenant administrator.
        String userToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN", "ADMIN"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(post("/internal/users/{id}/roles/TENANT_ADMIN", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/users/by-mobile").param("mobileNumber", PHONE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userRepository, tokenService);
    }
}
