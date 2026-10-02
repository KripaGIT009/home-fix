package com.homefix.provider.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.homefix.provider.config.InternalApiKeyFilter;
import com.homefix.provider.config.WebSecurityConfig;
import com.homefix.provider.eligibility.EligibilityQuery;
import com.homefix.provider.eligibility.ProviderEligibilityService;
import com.homefix.provider.eligibility.ProviderMatch;
import com.homefix.provider.service.ProviderAdminService;
import com.homefix.provider.service.ProviderAdminService.ProviderSummaryView;
import com.homefix.shared.security.HomefixSecurityAutoConfiguration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Web-layer tests for {@code GET /internal/providers/eligible} through the service's real security
 * chain ({@link WebSecurityConfig} plus the shared JWT and RBAC filters): the response is exactly
 * the Dispatch Engine's contract, the query parameters bind as the Dispatch Engine sends them, and
 * only the shared {@code X-Internal-Api-Key} reaches the handler — no key, a wrong key, and an
 * end-user token (even an admin's) are all refused.
 */
@WebMvcTest(controllers = InternalProviderController.class, properties = {
        "homefix.security.jwt-secret=" + InternalProviderControllerTest.JWT_SECRET,
        "homefix.provider.internal-api-key=" + InternalProviderControllerTest.INTERNAL_KEY
})
@Import(WebSecurityConfig.class)
@ImportAutoConfiguration(HomefixSecurityAutoConfiguration.class)
class InternalProviderControllerTest {

    static final String JWT_SECRET = "unit-test-signing-secret-that-is-32b+";
    static final String INTERNAL_KEY = "test-internal-key";

    private static final String PATH = "/internal/providers/eligible";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderEligibilityService eligibilityService;

    @MockBean
    private ProviderAdminService adminService;

    @Test
    void validServiceCredential_returnsTheDispatchContract() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID subcategoryId = UUID.randomUUID();
        when(eligibilityService.findEligible(any())).thenReturn(List.of(
                new ProviderMatch(providerId, 2.5, 0.75, 1.0, 0.92, 0.5, 0.5)));

        mockMvc.perform(get(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("subcategoryId", subcategoryId.toString())
                        .param("lat", "25.556")
                        .param("lon", "84.6603")
                        .param("radiusKm", "10.0")
                        .param("emergency", "true")
                        .param("skillTags", "plumbing")
                        .param("skillTags", "electrical"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(1))
                .andExpect(jsonPath("$.providers[0].providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.providers[0].distanceScore").value(0.75))
                .andExpect(jsonPath("$.providers[0].availabilityScore").value(1.0))
                .andExpect(jsonPath("$.providers[0].ratingScore").value(0.92))
                .andExpect(jsonPath("$.providers[0].skillScore").value(0.5))
                .andExpect(jsonPath("$.providers[0].performanceScore").value(0.5))
                // Internal ordering detail, not part of the contract.
                .andExpect(jsonPath("$.providers[0].distanceKm").doesNotExist());

        ArgumentCaptor<EligibilityQuery> query = ArgumentCaptor.forClass(EligibilityQuery.class);
        verify(eligibilityService).findEligible(query.capture());
        assertThat(query.getValue()).isEqualTo(new EligibilityQuery(
                subcategoryId, 25.556, 84.6603, 10.0, true, List.of("plumbing", "electrical")));
    }

    @Test
    void noEligibleProviders_isAnEmptyListNotAnError() throws Exception {
        when(eligibilityService.findEligible(any())).thenReturn(List.of());

        mockMvc.perform(get(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("lat", "25.556").param("lon", "84.6603").param("radiusKm", "10")
                        .param("skillTags", "plumbing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isEmpty());
    }

    @Test
    void missingOrMalformedCoordinates_is400InTheSharedEnvelope() throws Exception {
        mockMvc.perform(get(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("lon", "84.6603").param("radiusKm", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        mockMvc.perform(get(PATH)
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("lat", "north").param("lon", "84.6603").param("radiusKm", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(eligibilityService, never()).findEligible(any());
    }

    @Test
    void missingServiceCredential_returns401AndNeverSearches() throws Exception {
        mockMvc.perform(get(PATH).param("lat", "25.556").param("lon", "84.6603").param("radiusKm", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(eligibilityService, never()).findEligible(any());
    }

    @Test
    void wrongServiceCredential_returns401AndNeverSearches() throws Exception {
        mockMvc.perform(get(PATH)
                        .header(InternalApiKeyFilter.HEADER, "not-the-key")
                        .param("lat", "25.556").param("lon", "84.6603").param("radiusKm", "10"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(eligibilityService, never()).findEligible(any());
    }

    @Test
    void endUserTokenWithoutServiceCredential_isRefused() throws Exception {
        // A signed-in user, even staff, must not be able to list where providers are based.
        String userToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("SUPER_ADMIN", "DISPATCHER"))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get(PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .param("lat", "25.556").param("lon", "84.6603").param("radiusKm", "10"))
                .andExpect(status().isUnauthorized());

        verify(eligibilityService, never()).findEligible(any());
    }

    @Test
    void filterWithoutConfiguredKey_refusesEveryInternalRequest() throws Exception {
        // Fail closed: an unset key must not mean "no key required".
        InternalApiKeyFilter unconfigured = new InternalApiKeyFilter("  ");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader(InternalApiKeyFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        unconfigured.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void filterIgnoresNonInternalPaths() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(INTERNAL_KEY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/providers/me/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Passed straight through to the JWT/RBAC filters, which own that surface.
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ summaries (verification queue)

    @Test
    void summaries_returnTheNamesAndSkillsForTheServiceCredential() throws Exception {
        UUID named = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(adminService.summaries(List.of(named, unknown)))
                .thenReturn(List.of(new ProviderSummaryView(named, "Ravi Kumar", "plumbing")));

        mockMvc.perform(get("/internal/providers/summaries")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("ids", named.toString())
                        .param("ids", unknown.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(1))
                .andExpect(jsonPath("$.providers[0].id").value(named.toString()))
                .andExpect(jsonPath("$.providers[0].displayName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.providers[0].primarySkill").value("plumbing"));
    }

    @Test
    void summaries_withoutServiceCredential_are401AndNeverRead() throws Exception {
        mockMvc.perform(get("/internal/providers/summaries").param("ids", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_AUTH_FAILED"));

        verify(adminService, never()).summaries(any());
    }

    @Test
    void summaries_withAMalformedId_are400InTheSharedEnvelope() throws Exception {
        mockMvc.perform(get("/internal/providers/summaries")
                        .header(InternalApiKeyFilter.HEADER, INTERNAL_KEY)
                        .param("ids", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }
}
