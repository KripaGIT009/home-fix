package com.homefix.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.auth.config.InternalApiKeyFilter;
import com.homefix.auth.config.WebSecurityConfig;
import com.homefix.auth.domain.Role;
import com.homefix.auth.domain.UserAccount;
import com.homefix.auth.domain.UserAccountRepository;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for {@code GET /internal/users/{userId}/contact} through the service's real
 * security chain ({@link WebSecurityConfig} plus the shared JWT and RBAC filters), so the
 * service-credential guard is exercised exactly as deployed: no key, a wrong key, and an end-user
 * token are all refused; only the shared {@code X-Internal-Api-Key} reaches the handler.
 */
@WebMvcTest(controllers = InternalUserContactController.class, properties = {
        "homefix.security.jwt-secret=" + InternalUserContactControllerTest.JWT_SECRET,
        "homefix.auth.internal-api-key=" + InternalUserContactControllerTest.INTERNAL_KEY
})
@Import(WebSecurityConfig.class)
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalUserContactControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserAccountRepository userRepository;

    @Test
    void validServiceCredential_returnsContact() throws Exception {
        UserAccount account = UserAccount.createVerified("+919000000001", Role.CUSTOMER);
        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));

        mockMvc.perform(get("/internal/users/{id}/contact", account.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(account.getId().toString()))
                .andExpect(jsonPath("$.mobileNumber").value("+919000000001"))
                .andExpect(jsonPath("$.emailAddress").doesNotExist());
    }

    @Test
    void socialAccountWithoutPhone_returns200WithNullMobile() throws Exception {
        UserAccount account = UserAccount.createSocial(Role.CUSTOMER);
        when(userRepository.findById(account.getId())).thenReturn(Optional.of(account));

        mockMvc.perform(get("/internal/users/{id}/contact", account.getId())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(account.getId().toString()))
                .andExpect(jsonPath("$.mobileNumber").doesNotExist());
    }

    @Test
    void unknownUser_returns404InSharedEnvelope() throws Exception {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/users/{id}/contact", UUID.randomUUID())
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }

    @Test
    void malformedUserId_returns400() throws Exception {
        mockMvc.perform(get("/internal/users/not-a-uuid/contact")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void missingServiceCredential_returns401AndNeverTouchesAccounts() throws Exception {
        mockMvc.perform(get("/internal/users/{id}/contact", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(userRepository, never()).findById(any());
    }

    @Test
    void wrongServiceCredential_returns401() throws Exception {
        mockMvc.perform(get("/internal/users/{id}/contact", UUID.randomUUID())
                        .header(InternalApiKeyFilter.HEADER, "not-the-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(userRepository, never()).findById(any());
    }

    @Test
    void endUserTokenWithoutServiceCredential_isRefused() throws Exception {
        // A signed-in user, even staff, must not be able to read another user's phone number.
        String userToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN", "CUSTOMER"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/internal/users/{id}/contact", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isUnauthorized());

        verify(userRepository, never()).findById(any());
    }
}
