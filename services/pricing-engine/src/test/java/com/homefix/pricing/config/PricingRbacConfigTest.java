package com.homefix.pricing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;

import jakarta.servlet.FilterChain;

/**
 * Drives the real {@link RbacEnforcementFilter} over the rules registered by
 * {@link PricingRbacConfig}, so the assertions cover effective runtime behaviour (pattern ordering,
 * method matching, 401 vs 403) rather than the contents of a map.
 *
 * <p>{@link #customerPassesThroughOnEstimate()} and {@link #dispatcherPassesThroughOnEstimate()}
 * guard the relayed-token contract with the Booking Service: the estimate call carries the end
 * user's token, so every role that can drive a booking must be admitted.
 */
class PricingRbacConfigTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new PricingRbacConfig(props).registerEndpointRoles();
        filter = new RbacEnforcementFilter(props);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ helpers

    private void authenticateAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(roles).stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .toList()));
    }

    private MockHttpServletResponse invoke(String method, String path, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // ------------------------------------------------------------------ admin surface

    @Test
    void customerIsForbiddenOnAdminParameterUpdate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("PUT", "/admin/pricing/parameters", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminPassesThroughOnAdminParameterUpdate() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("PUT", "/admin/pricing/parameters", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedAdminParameterUpdateIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("PUT", "/admin/pricing/parameters", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminParameterReadIsForbiddenForProvider() throws Exception {
        authenticateAs("SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("GET", "/admin/pricing/parameters/" + UUID.randomUUID(), chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void adminTierPassesThroughOnPortalPricingConfig() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN")) {
            for (String[] call : portalConfigCalls()) {
                authenticateAs(role);
                FilterChain chain = mock(FilterChain.class);

                MockHttpServletResponse response = invoke(call[0], call[1], chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.OK.value());
                verify(chain, times(1)).doFilter(any(), any());
            }
        }
    }

    @Test
    void nonAdminRolesAreForbiddenOnPortalPricingConfig() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "DISPATCHER", "FINANCE_ADMIN")) {
            for (String[] call : portalConfigCalls()) {
                authenticateAs(role);
                FilterChain chain = mock(FilterChain.class);

                MockHttpServletResponse response = invoke(call[0], call[1], chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.FORBIDDEN.value());
                verify(chain, never()).doFilter(any(), any());
            }
        }
    }

    private static List<String[]> portalConfigCalls() {
        return List.of(new String[] {"GET", "/admin/pricing/config"},
                new String[] {"PUT", "/admin/pricing/config/" + UUID.randomUUID()});
    }

    // ------------------------------------------------------------------ estimate (relayed token)

    @Test
    void customerPassesThroughOnEstimate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/pricing/estimate", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void dispatcherPassesThroughOnEstimate() throws Exception {
        authenticateAs("DISPATCHER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/pricing/estimate", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedEstimateIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/pricing/estimate", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ provider overrides

    @Test
    void customerIsForbiddenOnOverrides() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/pricing/overrides", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void providerPassesThroughOnOverrides() throws Exception {
        authenticateAs("SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/pricing/overrides", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ public surfaces

    @Test
    void healthAndMetricsSurfacePassesThroughWithNoAuthentication() throws Exception {
        for (String path : List.of("/health/liveness", "/actuator/health", "/metrics", "/prometheus")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke("GET", path, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }
}
