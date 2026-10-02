package com.homefix.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Drives the real {@link RbacEnforcementFilter} over the rules {@link NotificationRbacConfig}
 * registers for the Admin Portal's template endpoints (Requirement 19.2, 19.6), which the
 * controller's own test bypasses.
 */
class NotificationRbacConfigTest {

    private static final String LIST = "/admin/notification-templates";
    private static final String ONE = "/admin/notification-templates/JOB_STARTED.CUSTOMER.PUSH";

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new NotificationRbacConfig(properties).registerEndpointRoles();
        filter = new RbacEnforcementFilter(properties);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private int status(String role, String method, String path) throws Exception {
        if (role != null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    UUID.randomUUID().toString(), "n/a", AuthorityUtils.createAuthorityList("ROLE_" + role)));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, mock(FilterChain.class));
        return response.getStatus();
    }

    @Test
    void adminAndSuperAdminMayListAndEditTemplates() throws Exception {
        for (String role : new String[] {"ADMIN", "SUPER_ADMIN"}) {
            assertThat(status(role, "GET", LIST)).as(role).isEqualTo(200);
            assertThat(status(role, "PUT", ONE)).as(role).isEqualTo(200);
        }
    }

    @Test
    void otherRolesAreRefused() throws Exception {
        for (String role : new String[] {"CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "FINANCE_ADMIN"}) {
            assertThat(status(role, "GET", LIST)).as(role).isEqualTo(403);
            assertThat(status(role, "PUT", ONE)).as(role).isEqualTo(403);
        }
        SecurityContextHolder.clearContext();
        assertThat(status(null, "GET", LIST)).isEqualTo(401);
    }

    @Test
    void healthSurfaceHasNoRule() throws Exception {
        assertThat(status(null, "GET", "/actuator/health")).isEqualTo(200);
        assertThat(status(null, "GET", "/prometheus")).isEqualTo(200);
    }
}
