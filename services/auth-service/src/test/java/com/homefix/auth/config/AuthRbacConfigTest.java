package com.homefix.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;

import jakarta.servlet.FilterChain;

/**
 * Drives the real {@link RbacEnforcementFilter} over the rules {@link AuthRbacConfig} registers
 * (Requirement 19.6): User Management is ADMIN / SUPER_ADMIN, and — just as important in this
 * service — the public {@code /auth/**} surface, {@code /internal/**} and the health surface stay
 * unruled, so adding the rules cannot turn an unauthenticated sign-in or a service-to-service
 * call into a 401.
 */
class AuthRbacConfigTest {

    private static final String USERS = "/admin/users";

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new AuthRbacConfig(properties).registerEndpointRoles();
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

    @ParameterizedTest
    @CsvSource({"GET, /admin/users", "GET, /admin/users/", "PATCH, /admin/users/" + "00000000-0000-0000-0000-000000000001/status"})
    void adminAndSuperAdminMayUseUserManagement(String method, String path) throws Exception {
        assertThat(status("ADMIN", method, path)).isEqualTo(200);
        assertThat(status("SUPER_ADMIN", method, path)).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "FINANCE_ADMIN", "DISPATCHER", "SUPPORT_AGENT",
            "TENANT_ADMIN"})
    void everyOtherRoleIsRefused(String role) throws Exception {
        assertThat(status(role, "GET", USERS)).isEqualTo(403);
        assertThat(status(role, "PATCH", USERS + "/" + UUID.randomUUID() + "/status")).isEqualTo(403);
    }

    @Test
    void unauthenticatedCallerIsRefused() throws Exception {
        assertThat(status(null, "GET", USERS)).isEqualTo(401);
        assertThat(status(null, "PATCH", USERS + "/" + UUID.randomUUID() + "/status")).isEqualTo(401);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /auth/register/otp",
            "POST, /auth/register/verify",
            "POST, /auth/login/password",
            "POST, /auth/login/social",
            "POST, /auth/token/refresh",
            "POST, /auth/logout",
            "GET, /auth/introspect",
            "GET, /internal/users/00000000-0000-0000-0000-000000000001/contact",
            "GET, /actuator/health",
            "GET, /prometheus"})
    void publicInternalAndHealthSurfacesHaveNoRule(String method, String path) throws Exception {
        assertThat(status(null, method, path)).isEqualTo(200);
    }
}
