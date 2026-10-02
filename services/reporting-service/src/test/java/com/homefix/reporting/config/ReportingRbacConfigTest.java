package com.homefix.reporting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;

import jakarta.servlet.FilterChain;

/**
 * Drives the real {@link RbacEnforcementFilter} over the rules {@link ReportingRbacConfig}
 * registers (Requirement 20.6): both report surfaces admit exactly the reporting tier (ADMIN,
 * SUPER_ADMIN, FINANCE_ADMIN). The Finance_Admin-only report types are enforced per request by
 * {@code ReportAuthorization}, covered in the controller tests.
 */
class ReportingRbacConfigTest {

    private static final List<String[]> CALLS = List.of(
            new String[] {"GET", "/admin/reports/types"},
            new String[] {"POST", "/admin/reports"},
            new String[] {"POST", "/admin/reports/export"},
            new String[] {"POST", "/reports"},
            new String[] {"POST", "/reports/export"});

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new ReportingRbacConfig(properties).registerEndpointRoles();
        filter = new RbacEnforcementFilter(properties);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private int status(String role, String method, String path) throws Exception {
        SecurityContextHolder.clearContext();
        if (role != null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID().toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_" + role)));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, mock(FilterChain.class));
        return response.getStatus();
    }

    @Test
    void reportingTierPassesOnEveryReportPath() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN")) {
            for (String[] call : CALLS) {
                assertThat(status(role, call[0], call[1])).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(200);
            }
        }
    }

    @Test
    void otherRolesAreForbiddenOnEveryReportPath() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "DISPATCHER", "SUPPORT_AGENT")) {
            for (String[] call : CALLS) {
                assertThat(status(role, call[0], call[1])).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(403);
            }
        }
    }

    @Test
    void anonymousCallersAreUnauthorized() throws Exception {
        for (String[] call : CALLS) {
            assertThat(status(null, call[0], call[1])).as(call[0] + " " + call[1]).isEqualTo(401);
        }
    }

    @Test
    void healthSurfaceHasNoRule() throws Exception {
        assertThat(status(null, "GET", "/actuator/health")).isEqualTo(200);
    }
}
