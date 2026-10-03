package com.homefix.provider.config;

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
 * {@link ProviderRbacConfig}, so the assertions cover the effective runtime behaviour (pattern
 * ordering, method matching, 401 vs 403) rather than the contents of a map.
 *
 * <p>The final test is the regression guard for the health/metrics surface: because the filter runs
 * inside the security chain even for {@code permitAll()} paths, any rule accidentally covering
 * {@code /health/**} or {@code /actuator/**} would turn every Kubernetes probe into a 401.
 */
class ProviderRbacConfigTest {

    private RbacEnforcementFilter filter;
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new ProviderRbacConfig(props).registerEndpointRoles();
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
                        List.of(roles).stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    private MockHttpServletResponse invoke(String method, String path, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // ------------------------------------------------------------------ mutating self-service

    @Test
    void customerIsForbiddenOnProfileUpdate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("PUT", "/providers/" + providerId + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void serviceProviderPassesThroughOnEveryMutatingEndpoint() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        for (String suffix : List.of("/profile", "/radius", "/availability", "/emergency-availability")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke("PUT", "/providers/" + providerId + suffix, chain);

            assertThat(response.getStatus()).as(suffix).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Test
    void adminPassesThroughOnProfileUpdate() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("PUT", "/providers/" + providerId + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedProfileUpdateIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("PUT", "/providers/" + providerId + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void unauthenticatedProfileReadIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/providers/" + providerId + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ money-facing endpoints

    @Test
    void financeAdminPassesThroughOnSettlementsAndEarnings() throws Exception {
        authenticateAs("FINANCE_ADMIN");

        FilterChain settlementChain = mock(FilterChain.class);
        assertThat(invoke("POST", "/providers/" + providerId + "/settlements", settlementChain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(settlementChain, times(1)).doFilter(any(), any());

        FilterChain earningsChain = mock(FilterChain.class);
        assertThat(invoke("GET", "/providers/" + providerId + "/earnings", earningsChain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(earningsChain, times(1)).doFilter(any(), any());
    }

    @Test
    void serviceProviderPassesThroughOnSettlementsAndEarnings() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertThat(invoke("POST", "/providers/" + providerId + "/settlements", mock(FilterChain.class))
                .getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(invoke("GET", "/providers/" + providerId + "/earnings", mock(FilterChain.class))
                .getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void customerIsForbiddenOnSettlementsAndEarnings() throws Exception {
        authenticateAs("CUSTOMER");

        assertThat(invoke("POST", "/providers/" + providerId + "/settlements", mock(FilterChain.class))
                .getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(invoke("GET", "/providers/" + providerId + "/earnings", mock(FilterChain.class))
                .getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void dispatcherIsForbiddenOnEarningsButMayReadTheProfile() throws Exception {
        authenticateAs("DISPATCHER");

        // Earnings are money data: dispatch has no business there.
        assertThat(invoke("GET", "/providers/" + providerId + "/earnings", mock(FilterChain.class))
                .getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());

        // The profile drives job offers, so dispatch may read it.
        FilterChain profileChain = mock(FilterChain.class);
        assertThat(invoke("GET", "/providers/" + providerId + "/profile", profileChain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(profileChain, times(1)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ profile read tier

    @Test
    void supportAgentAndServiceProviderMayReadTheProfile() throws Exception {
        for (String role : List.of("SUPPORT_AGENT", "SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN")) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response =
                    invoke("GET", "/providers/" + providerId + "/profile", chain);

            assertThat(response.getStatus()).as(role).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Test
    void customerIsForbiddenOnProfileRead() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/providers/" + providerId + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ public surface guard

    // ------------------------------------------------------------------ Admin Portal provider management

    @Test
    void adminTierPassesThroughOnAdminProviderListAndStatusChange() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN")) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            for (String[] call : List.of(
                    new String[] {"GET", "/admin/providers"},
                    new String[] {"PATCH", "/admin/providers/" + providerId + "/status"})) {
                FilterChain chain = mock(FilterChain.class);

                MockHttpServletResponse response = invoke(call[0], call[1], chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.OK.value());
                verify(chain, times(1)).doFilter(any(), any());
            }
        }
    }

    @Test
    void nonAdminRolesAreForbiddenOnAdminProviderManagement() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "DISPATCHER",
                "FINANCE_ADMIN")) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            for (String[] call : List.of(
                    new String[] {"GET", "/admin/providers"},
                    new String[] {"PATCH", "/admin/providers/" + providerId + "/status"})) {
                FilterChain chain = mock(FilterChain.class);

                assertThat(invoke(call[0], call[1], chain).getStatus())
                        .as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.FORBIDDEN.value());
                verify(chain, never()).doFilter(any(), any());
            }
        }
    }

    @Test
    void unauthenticatedAdminProviderListIsUnauthorized() throws Exception {
        assertThat(invoke("GET", "/admin/providers", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------ Tenants (Requirement MT-10)

    private static final List<String[]> ADMIN_TENANT_CALLS = List.of(
            new String[] {"GET", "/admin/tenants"},
            new String[] {"POST", "/admin/tenants"},
            new String[] {"PUT", "/admin/tenants/7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6"},
            new String[] {"GET", "/admin/tenants/7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6/members"},
            new String[] {"POST", "/admin/tenants/7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6/admins"},
            new String[] {"DELETE", "/admin/tenants/7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6/providers/x"});

    private static final List<String[]> TENANT_PORTAL_CALLS = List.of(
            new String[] {"GET", "/tenant/me"},
            new String[] {"GET", "/tenant/providers"},
            new String[] {"POST", "/tenant/providers"},
            new String[] {"DELETE", "/tenant/providers/7e0a1f3c-2b4d-4c6e-8f10-a1b2c3d4e5f6"});

    private void assertCalls(List<String[]> calls, List<String> roles, int expectedStatus) throws Exception {
        for (String role : roles) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            for (String[] call : calls) {
                FilterChain chain = mock(FilterChain.class);

                assertThat(invoke(call[0], call[1], chain).getStatus())
                        .as(role + " " + call[0] + " " + call[1]).isEqualTo(expectedStatus);
                verify(chain, times(expectedStatus == HttpStatus.OK.value() ? 1 : 0)).doFilter(any(), any());
            }
        }
    }

    @Test
    void tenantRegistryIsForPlatformAdminsOnly() throws Exception {
        assertCalls(ADMIN_TENANT_CALLS, List.of("ADMIN", "SUPER_ADMIN"), HttpStatus.OK.value());
        // A Tenant_Admin never reaches platform administration (Requirement MT-10.3).
        assertCalls(ADMIN_TENANT_CALLS, List.of("TENANT_ADMIN", "SERVICE_PROVIDER", "DISPATCHER",
                "FINANCE_ADMIN", "CUSTOMER"), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void tenantPortalIsForTenantAdminsOnly() throws Exception {
        assertCalls(TENANT_PORTAL_CALLS, List.of("TENANT_ADMIN"), HttpStatus.OK.value());
        assertCalls(TENANT_PORTAL_CALLS, List.of("ADMIN", "SUPER_ADMIN", "SERVICE_PROVIDER", "CUSTOMER"),
                HttpStatus.FORBIDDEN.value());
    }

    @Test
    void unauthenticatedTenantCallsAreUnauthorized() throws Exception {
        assertThat(invoke("GET", "/admin/tenants", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(invoke("GET", "/tenant/me", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------ bank account (Req 4.9, 14.2)

    @Test
    void bankAccountWriteIsForTheProviderAndPlatformAdmins() throws Exception {
        List<String[]> put = List.<String[]>of(new String[] {"PUT", "/providers/" + providerId + "/bank-account"});
        assertCalls(put, List.of("SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN"), HttpStatus.OK.value());
        assertCalls(put, List.of("CUSTOMER", "DISPATCHER", "SUPPORT_AGENT", "TENANT_ADMIN", "FINANCE_ADMIN"),
                HttpStatus.FORBIDDEN.value());
        SecurityContextHolder.clearContext();
        assertThat(invoke("PUT", "/providers/" + providerId + "/bank-account", mock(FilterChain.class))
                .getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void bankAccountVerificationIsForAdminsAndFinance() throws Exception {
        List<String[]> verify = List.<String[]>of(
                new String[] {"POST", "/admin/providers/" + providerId + "/bank-account/verification"});
        assertCalls(verify, List.of("ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN"), HttpStatus.OK.value());
        assertCalls(verify, List.of("SERVICE_PROVIDER", "CUSTOMER", "DISPATCHER", "SUPPORT_AGENT", "TENANT_ADMIN"),
                HttpStatus.FORBIDDEN.value());
        SecurityContextHolder.clearContext();
        assertThat(invoke("POST", "/admin/providers/" + providerId + "/bank-account/verification",
                mock(FilterChain.class)).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void anyOtherAdminProviderPostIsForPlatformAdminsOnly() throws Exception {
        List<String[]> other = List.<String[]>of(new String[] {"POST", "/admin/providers/" + providerId + "/other"});
        assertCalls(other, List.of("ADMIN", "SUPER_ADMIN"), HttpStatus.OK.value());
        assertCalls(other, List.of("FINANCE_ADMIN", "SERVICE_PROVIDER"), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void healthAndMetricsSurfacePassesThroughWithNoAuthentication() throws Exception {
        for (String path : List.of("/health/liveness", "/health/readiness", "/actuator/health",
                "/metrics", "/prometheus")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke("GET", path, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }
}
