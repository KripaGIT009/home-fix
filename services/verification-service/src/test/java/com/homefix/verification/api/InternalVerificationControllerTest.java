package com.homefix.verification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.shared.security.HomefixSecurityAutoConfiguration;
import com.homefix.verification.api.dto.ApprovedProvidersRequest;
import com.homefix.verification.config.InternalApiKeyFilter;
import com.homefix.verification.config.WebSecurityConfig;
import com.homefix.verification.domain.Verification;
import com.homefix.verification.domain.VerificationStatus;
import com.homefix.verification.service.VerificationException;
import com.homefix.verification.service.VerificationService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for the internal surface — {@code POST /internal/verifications/approved}, the
 * {@code /statuses} lookup and the {@code /suspend} / {@code /reinstate} actions — through the
 * service's real security chain ({@link WebSecurityConfig} plus the shared JWT and RBAC filters): the approved
 * subset is returned for the shared {@code X-Internal-Api-Key}, and no key, a wrong key, or an
 * end-user token (even an admin's) is refused before the service is touched.
 */
@WebMvcTest(controllers = InternalVerificationController.class, properties = {
        "homefix.security.jwt-secret=" + InternalVerificationControllerTest.JWT_SECRET,
        "homefix.verification.internal-api-key=" + InternalVerificationControllerTest.INTERNAL_KEY
})
@Import(WebSecurityConfig.class)
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalVerificationControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    private static final String PATH = "/internal/verifications/approved";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerificationService verificationService;

    private static String body(List<UUID> ids) {
        return "{\"providerIds\":[" + ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}";
    }

    @Test
    void validServiceCredential_returnsTheApprovedSubset() throws Exception {
        UUID approved = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        when(verificationService.approvedAmong(anyCollection())).thenReturn(Set.of(approved));

        mockMvc.perform(post(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(approved, pending))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedProviderIds.length()").value(1))
                .andExpect(jsonPath("$.approvedProviderIds[0]").value(approved.toString()));

        verify(verificationService).approvedAmong(List.of(approved, pending));
    }

    @Test
    void emptyIdList_isAnEmptyAnswer() throws Exception {
        when(verificationService.approvedAmong(anyCollection())).thenReturn(Set.of());

        mockMvc.perform(post(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedProviderIds").isEmpty());
    }

    @Test
    void missingOrOversizedIdList_is400() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= ApprovedProvidersRequest.MAX_PROVIDER_IDS; i++) {
            tooMany.add(UUID.randomUUID());
        }
        mockMvc.perform(post(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tooMany)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(verificationService, never()).approvedAmong(any());
    }

    @Test
    void missingServiceCredential_returns401AndNeverQueries() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(verificationService, never()).approvedAmong(any());
    }

    @Test
    void wrongServiceCredential_returns401AndNeverQueries() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(InternalApiKeyFilter.HEADER, "not-the-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(verificationService, never()).approvedAmong(any());
    }

    @Test
    void endUserTokenWithoutServiceCredential_isRefused() throws Exception {
        String userToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN", "DISPATCHER"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());

        verify(verificationService, never()).approvedAmong(any());
    }

    @Test
    void filterWithoutConfiguredKey_refusesEveryInternalRequest() throws Exception {
        // Fail closed: an unset key must not mean "no key required".
        InternalApiKeyFilter unconfigured = new InternalApiKeyFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader(InternalApiKeyFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        unconfigured.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    // ------------------------------------------------------------------ statuses (Admin provider list)

    @Test
    void statuses_returnsEachKnownProvidersStatusByName() throws Exception {
        UUID approved = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        Map<UUID, VerificationStatus> found = new LinkedHashMap<>();
        found.put(approved, VerificationStatus.APPROVED);
        found.put(suspended, VerificationStatus.SUSPENDED);
        when(verificationService.statusesAmong(anyCollection())).thenReturn(found);

        mockMvc.perform(post("/internal/verifications/statuses")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(approved, suspended, unknown))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses['" + approved + "']").value("APPROVED"))
                .andExpect(jsonPath("$.statuses['" + suspended + "']").value("SUSPENDED"))
                .andExpect(jsonPath("$.statuses['" + unknown + "']").doesNotExist());

        verify(verificationService).statusesAmong(List.of(approved, suspended, unknown));
    }

    @Test
    void statuses_withoutServiceCredential_is401AndNeverQueries() throws Exception {
        mockMvc.perform(post("/internal/verifications/statuses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(verificationService, never()).statusesAmong(any());
    }

    @Test
    void statuses_missingIdList_is400() throws Exception {
        mockMvc.perform(post("/internal/verifications/statuses")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------ suspend / reinstate

    private static Verification inStatus(UUID providerId, VerificationStatus target) {
        UUID admin = UUID.randomUUID();
        Verification v = Verification.create(providerId);
        v.transitionTo(VerificationStatus.DOCUMENT_SUBMITTED, providerId, "submitted");
        v.transitionTo(VerificationStatus.DOCUMENT_VERIFIED, admin, "verified");
        v.transitionTo(VerificationStatus.BACKGROUND_CHECK_PENDING, admin, "check started");
        v.transitionTo(VerificationStatus.BACKGROUND_CHECK_COMPLETED, admin, "check completed");
        v.transitionTo(VerificationStatus.APPROVED, admin, "approved");
        if (target == VerificationStatus.SUSPENDED) {
            v.transitionTo(VerificationStatus.SUSPENDED, admin, "suspended");
        }
        return v;
    }

    private static String actorBody(UUID actor, String reason) {
        return reason == null
                ? "{\"actorId\":\"" + actor + "\"}"
                : "{\"actorId\":\"" + actor + "\",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void suspend_drivesTheSuspendTransitionWithTheForwardedAdmin() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(verificationService.suspend(providerId, admin, "complaint upheld"))
                .thenReturn(inStatus(providerId, VerificationStatus.SUSPENDED));

        mockMvc.perform(post("/internal/verifications/" + providerId + "/suspend")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actorBody(admin, "complaint upheld")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void reinstate_drivesTheReinstateTransition() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(verificationService.reinstate(providerId, admin, null))
                .thenReturn(inStatus(providerId, VerificationStatus.APPROVED));

        mockMvc.perform(post("/internal/verifications/" + providerId + "/reinstate")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actorBody(admin, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void reinstate_ofAProviderNotSuspended_is409() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(verificationService.reinstate(providerId, admin, null))
                .thenThrow(new VerificationException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                        "Only a SUSPENDED provider can be reinstated"));

        mockMvc.perform(post("/internal/verifications/" + providerId + "/reinstate")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actorBody(admin, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void statusChange_withoutActor_is400AndNeverTransitions() throws Exception {
        UUID providerId = UUID.randomUUID();

        mockMvc.perform(post("/internal/verifications/" + providerId + "/suspend")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"no actor\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(verificationService, never()).suspend(any(), any(), any());
    }

    @Test
    void statusChange_withAnEndUserTokenInsteadOfTheCredential_isRefused() throws Exception {
        UUID providerId = UUID.randomUUID();
        String adminToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(post("/internal/verifications/" + providerId + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actorBody(UUID.randomUUID(), null)))
                .andExpect(status().isUnauthorized());

        verify(verificationService, never()).suspend(any(), any(), any());
    }
}
