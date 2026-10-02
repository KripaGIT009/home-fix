package com.homefix.admin.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import jakarta.servlet.FilterChain;

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

/**
 * Drives the real {@link RbacEnforcementFilter} over the rules {@link AdminRbacConfig} registers
 * (Requirement 19.6, 19.7): System Configuration is SUPER_ADMIN only, the Audit Logs view and the
 * rest of {@code /admin/**} are Admin tier, and the health surface stays unruled.
 */
class AdminRbacConfigTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new AdminRbacConfig(properties).registerEndpointRoles();
        filter = new RbacEnforcementFilter(properties);
        SecurityContextHolder.clearContext();
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
    void systemConfigurationIsSuperAdminOnly() throws Exception {
        assertThat(status("SUPER_ADMIN", "GET", "/admin/system-config")).isEqualTo(200);
        assertThat(status("SUPER_ADMIN", "PUT", "/admin/system-config")).isEqualTo(200);
        assertThat(status("SUPER_ADMIN", "PUT", "/admin/system-config/audit.pageSize")).isEqualTo(200);
        assertThat(status("ADMIN", "GET", "/admin/system-config")).isEqualTo(403);
        assertThat(status("ADMIN", "PUT", "/admin/system-config")).isEqualTo(403);
        assertThat(status("ADMIN", "PATCH", "/admin/system-config")).isEqualTo(403);
    }

    @Test
    void auditLogsAreAdminTier() throws Exception {
        assertThat(status("ADMIN", "GET", "/admin/audit-logs")).isEqualTo(200);
        assertThat(status("SUPER_ADMIN", "GET", "/admin/audit-logs")).isEqualTo(200);
        assertThat(status("SUPPORT_AGENT", "GET", "/admin/audit-logs")).isEqualTo(403);
        assertThat(status("CUSTOMER", "GET", "/admin/audit-logs")).isEqualTo(403);
        assertThat(status(null, "GET", "/admin/audit-logs")).isEqualTo(401);
    }

    @Test
    void everyMethodOnTheRestOfTheAdminSurfaceIsAdminTier() throws Exception {
        for (String method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
            assertThat(status("CUSTOMER", method, "/admin/dashboard")).as(method).isEqualTo(403);
            assertThat(status("ADMIN", method, "/admin/dashboard")).as(method).isEqualTo(200);
        }
    }

    @Test
    void healthSurfaceHasNoRule() throws Exception {
        assertThat(status(null, "GET", "/actuator/health")).isEqualTo(200);
    }
}
