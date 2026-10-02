package com.homefix.complaint.config;

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
 * {@link ComplaintRbacConfig}, so the assertions cover effective runtime behaviour (pattern
 * ordering, method matching, 401 vs 403) rather than the contents of a map.
 *
 * <p>{@link #healthAndMetricsSurfacePassesThroughWithNoAuthentication()} is the regression guard
 * for the public operational surface: the filter runs inside the security chain even for
 * {@code permitAll()} paths, so any rule that leaked onto {@code /actuator/**} or {@code /metrics}
 * would turn every unauthenticated Kubernetes probe and Prometheus scrape into a 401.
 */
class ComplaintRbacConfigTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new ComplaintRbacConfig(props).registerEndpointRoles();
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

    private static String refundPath() {
        return "/complaints/" + UUID.randomUUID() + "/refund";
    }

    private static String statusPath() {
        return "/complaints/" + UUID.randomUUID() + "/status";
    }

    private static String disputePath() {
        return "/complaints/" + UUID.randomUUID() + "/dispute";
    }

    // ------------------------------------------------------------------ staff-only surface

    @Test
    void customerIsForbiddenOnRefundApproval() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", refundPath(), chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void customerIsForbiddenOnStatusChange() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", statusPath(), chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void customerIsForbiddenOnStats() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/complaints/stats", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void customerIsForbiddenOnDispute() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", disputePath(), chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void supportAgentPassesThroughOnRefundStatusAndStats() throws Exception {
        authenticateAs("SUPPORT_AGENT");

        for (String[] call : new String[][] {
                {"POST", refundPath()}, {"POST", statusPath()}, {"GET", "/complaints/stats"}}) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke(call[0], call[1], chain);

            assertThat(response.getStatus()).as(call[0] + " " + call[1])
                    .isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Test
    void adminPassesThroughOnRefundStatusAndStats() throws Exception {
        authenticateAs("ADMIN");

        for (String[] call : new String[][] {
                {"POST", refundPath()}, {"POST", statusPath()}, {"GET", "/complaints/stats"}}) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke(call[0], call[1], chain);

            assertThat(response.getStatus()).as(call[0] + " " + call[1])
                    .isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Test
    void serviceProviderIsForbiddenOnEveryStaffPath() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        for (String[] call : new String[][] {
                {"POST", refundPath()}, {"POST", statusPath()}, {"POST", disputePath()},
                {"GET", "/complaints/stats"}}) {
            assertThat(invoke(call[0], call[1], mock(FilterChain.class)).getStatus())
                    .as(call[0] + " " + call[1])
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    @Test
    void unauthenticatedStatsIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/complaints/stats", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ customer surface

    @Test
    void customerPassesThroughOnComplaintCreation() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/complaints", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    /**
     * The bare {@code POST /complaints} rule must not widen the staff moderation paths: a CUSTOMER
     * allowed to create still gets a 403 on the nested staff paths (covered above), and a staff
     * caller is still matched by the staff rule declared first.
     */
    @Test
    void complaintCreationRuleDoesNotShadowNestedStaffPaths() throws Exception {
        authenticateAs("CUSTOMER");

        assertThat(invoke("POST", "/complaints", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        assertThat(invoke("POST", refundPath(), mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ------------------------------------------------------------------ Admin Portal surface

    private static String adminComplaintPath() {
        return "/admin/complaints/" + UUID.randomUUID();
    }

    @Test
    void supportTierPassesThroughOnTheAdminComplaintListAndUpdate() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT")) {
            authenticateAs(role);
            for (String[] call : new String[][] {
                    {"GET", "/admin/complaints"}, {"PATCH", adminComplaintPath()}}) {
                FilterChain chain = mock(FilterChain.class);

                MockHttpServletResponse response = invoke(call[0], call[1], chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.OK.value());
                verify(chain, times(1)).doFilter(any(), any());
            }
        }
    }

    @Test
    void otherRolesAreForbiddenOnTheAdminComplaintSurface() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "FINANCE_ADMIN", "DISPATCHER")) {
            authenticateAs(role);
            for (String[] call : new String[][] {
                    {"GET", "/admin/complaints"}, {"PATCH", adminComplaintPath()}}) {
                assertThat(invoke(call[0], call[1], mock(FilterChain.class)).getStatus())
                        .as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.FORBIDDEN.value());
            }
        }
    }

    @Test
    void unauthenticatedAdminComplaintListIsUnauthorized() throws Exception {
        assertThat(invoke("GET", "/admin/complaints", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    // ------------------------------------------------------------------ public surface

    @Test
    void healthAndMetricsSurfacePassesThroughWithNoAuthentication() throws Exception {
        for (String path : List.of("/actuator/health", "/health/liveness", "/metrics",
                "/prometheus")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke("GET", path, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }
}
