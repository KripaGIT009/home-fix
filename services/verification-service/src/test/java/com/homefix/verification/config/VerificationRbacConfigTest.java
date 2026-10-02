package com.homefix.verification.config;

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
 * {@link VerificationRbacConfig}, so the assertions cover the effective runtime behaviour (pattern
 * ordering, method matching, 401 vs 403) rather than the contents of a map.
 *
 * <p>Two cases are ordering regression guards: {@code DISPATCHER} must still reach
 * {@code job-assignment-eligibility} (it would be shadowed if the catch-all
 * {@code GET /verifications/}&#42; rule were registered first), and the health/metrics surface must
 * stay anonymous because the filter runs inside the security chain even for {@code permitAll()}
 * paths.
 */
class VerificationRbacConfigTest {

    private RbacEnforcementFilter filter;
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new VerificationRbacConfig(props).registerEndpointRoles();
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

    // ------------------------------------------------------------------ admin workflow surface

    @Test
    void customerIsForbiddenOnAdminApprove() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/admin/verifications/" + providerId + "/approve", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminPassesThroughOnAdminApprove() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/admin/verifications/" + providerId + "/approve", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void superAdminPassesThroughOnEveryAdminAction() throws Exception {
        authenticateAs("SUPER_ADMIN");

        for (String action : List.of("/verify-documents", "/background-check-result", "/approve",
                "/reject", "/suspend")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response =
                    invoke("POST", "/admin/verifications/" + providerId + action, chain);

            assertThat(response.getStatus()).as(action).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }

    @Test
    void serviceProviderIsForbiddenOnAdminApprove() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertThat(invoke("POST", "/admin/verifications/" + providerId + "/approve",
                mock(FilterChain.class)).getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void unauthenticatedAdminApproveIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/admin/verifications/" + providerId + "/approve", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ Admin Portal verification queue

    @Test
    void adminTierPassesThroughOnEveryVerificationQueueEndpoint() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN")) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            for (String[] call : List.of(
                    new String[] {"GET", "/admin/verification/queue"},
                    new String[] {"GET", "/admin/verification/" + providerId + "/documents"},
                    new String[] {"POST", "/admin/verification/" + providerId + "/decision"})) {
                FilterChain chain = mock(FilterChain.class);

                MockHttpServletResponse response = invoke(call[0], call[1], chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.OK.value());
                verify(chain, times(1)).doFilter(any(), any());
            }
        }
    }

    @Test
    void nonAdminRolesAreForbiddenOnTheVerificationQueue() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "DISPATCHER",
                "FINANCE_ADMIN")) {
            SecurityContextHolder.clearContext();
            authenticateAs(role);
            for (String[] call : List.of(
                    new String[] {"GET", "/admin/verification/queue"},
                    new String[] {"GET", "/admin/verification/" + providerId + "/documents"},
                    new String[] {"POST", "/admin/verification/" + providerId + "/decision"})) {
                FilterChain chain = mock(FilterChain.class);

                assertThat(invoke(call[0], call[1], chain).getStatus())
                        .as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.FORBIDDEN.value());
                verify(chain, never()).doFilter(any(), any());
            }
        }
    }

    @Test
    void unauthenticatedVerificationQueueIsUnauthorized() throws Exception {
        assertThat(invoke("GET", "/admin/verification/queue", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    void patchOnTheAdminSurfaceIsAdminOnly() throws Exception {
        authenticateAs("SERVICE_PROVIDER");
        assertThat(invoke("PATCH", "/admin/verification/" + providerId, mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());

        SecurityContextHolder.clearContext();
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);
        assertThat(invoke("PATCH", "/admin/verification/" + providerId, chain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ document submission

    @Test
    void customerIsForbiddenOnDocumentSubmission() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/verifications/" + providerId + "/documents", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void serviceProviderPassesThroughOnDocumentSubmission() throws Exception {
        authenticateAs("SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/verifications/" + providerId + "/documents", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void dispatcherIsForbiddenOnDocumentSubmission() throws Exception {
        authenticateAs("DISPATCHER");

        assertThat(invoke("POST", "/verifications/" + providerId + "/documents",
                mock(FilterChain.class)).getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void unauthenticatedDocumentSubmissionIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/verifications/" + providerId + "/documents", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ dispatch eligibility gate

    @Test
    void dispatcherPassesThroughOnJobAssignmentEligibility() throws Exception {
        authenticateAs("DISPATCHER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("GET", "/verifications/" + providerId + "/job-assignment-eligibility", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void customerIsForbiddenOnJobAssignmentEligibility() throws Exception {
        authenticateAs("CUSTOMER");

        assertThat(invoke("GET", "/verifications/" + providerId + "/job-assignment-eligibility",
                mock(FilterChain.class)).getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ------------------------------------------------------------------ record read

    @Test
    void supportAgentMayReadTheRecordButDispatcherMayNot() throws Exception {
        authenticateAs("SUPPORT_AGENT");
        FilterChain supportChain = mock(FilterChain.class);
        assertThat(invoke("GET", "/verifications/" + providerId, supportChain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(supportChain, times(1)).doFilter(any(), any());

        SecurityContextHolder.clearContext();
        authenticateAs("DISPATCHER");
        FilterChain dispatchChain = mock(FilterChain.class);
        assertThat(invoke("GET", "/verifications/" + providerId, dispatchChain).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(dispatchChain, never()).doFilter(any(), any());
    }

    @Test
    void unauthenticatedRecordReadIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/verifications/" + providerId, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ public surface guard

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
