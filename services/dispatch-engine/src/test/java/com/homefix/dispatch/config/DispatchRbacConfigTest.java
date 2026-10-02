package com.homefix.dispatch.config;

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
 * Drives the real {@link RbacEnforcementFilter} over the rules {@link DispatchRbacConfig}
 * registers for the admin weight endpoints (Requirement 19.5, 19.6), which the weight controller's
 * own test bypasses. The job-offer rules are exercised through MockMvc in
 * {@code JobOfferControllerTest}.
 */
class DispatchRbacConfigTest {

    private static final String WEIGHTS = "/admin/dispatch/weights";
    private static final String CONFIG = "/admin/dispatch/config";

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new DispatchRbacConfig(properties).registerEndpointRoles();
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
    void onlySuperAdminMayChangeTheWeights() throws Exception {
        assertThat(status("SUPER_ADMIN", "PUT", WEIGHTS)).isEqualTo(200);
        assertThat(status("ADMIN", "PUT", WEIGHTS)).isEqualTo(403);
        assertThat(status("SERVICE_PROVIDER", "PUT", WEIGHTS)).isEqualTo(403);
    }

    @Test
    void adminAndSuperAdminMayReadTheWeights() throws Exception {
        assertThat(status("ADMIN", "GET", WEIGHTS)).isEqualTo(200);
        assertThat(status("SUPER_ADMIN", "GET", WEIGHTS)).isEqualTo(200);
        assertThat(status("DISPATCHER", "GET", WEIGHTS)).isEqualTo(403);
    }

    @Test
    void dispatcherMayAlsoReadTheDispatchRules() throws Exception {
        // Anonymous first: status(...) only sets, never clears, the security context.
        assertThat(status(null, "GET", CONFIG)).isEqualTo(401);
        assertThat(status("ADMIN", "GET", CONFIG)).isEqualTo(200);
        assertThat(status("SUPER_ADMIN", "GET", CONFIG)).isEqualTo(200);
        assertThat(status("DISPATCHER", "GET", CONFIG)).isEqualTo(200);
        assertThat(status("SERVICE_PROVIDER", "GET", CONFIG)).isEqualTo(403);
        assertThat(status("CUSTOMER", "GET", CONFIG)).isEqualTo(403);
    }

    @Test
    void onlySuperAdminMayChangeTheDispatchRules() throws Exception {
        assertThat(status("SUPER_ADMIN", "PUT", CONFIG)).isEqualTo(200);
        assertThat(status("ADMIN", "PUT", CONFIG)).isEqualTo(403);
        assertThat(status("DISPATCHER", "PUT", CONFIG)).isEqualTo(403);
    }

    @Test
    void offerListIsCoveredByTheProviderRuleWithoutATrailingSegment() throws Exception {
        assertThat(status(null, "GET", "/dispatch/offers")).isEqualTo(401);
        assertThat(status("SERVICE_PROVIDER", "GET", "/dispatch/offers")).isEqualTo(200);
    }

    @Test
    void healthSurfaceHasNoRule() throws Exception {
        assertThat(status(null, "GET", "/actuator/health")).isEqualTo(200);
    }
}
