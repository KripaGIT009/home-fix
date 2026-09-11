package com.homefix.customer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;

import jakarta.servlet.FilterChain;

/**
 * Drives the real {@link RbacEnforcementFilter} against the rules {@link CustomerRbacConfig}
 * registers, so the allow-list is verified end-to-end rather than by inspecting the map.
 */
class CustomerRbacConfigTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new CustomerRbacConfig(properties).registerEndpointRoles();
        filter = new RbacEnforcementFilter(properties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), null,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private MockHttpServletResponse dispatch(String method, String path, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void serviceProviderOnlyCaller_isForbiddenOnProfileUpdate() throws Exception {
        authenticate("ROLE_SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                dispatch("PUT", "/customers/" + CUSTOMER_ID + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void customerCaller_passesProfileUpdate() throws Exception {
        authenticate("ROLE_CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                dispatch("PUT", "/customers/" + CUSTOMER_ID + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void supportAgentCaller_passesProfileUpdate() throws Exception {
        authenticate("ROLE_SUPPORT_AGENT");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                dispatch("PUT", "/customers/" + CUSTOMER_ID + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void unauthenticatedCaller_isUnauthorizedOnProfileUpdate() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                dispatch("PUT", "/customers/" + CUSTOMER_ID + "/profile", chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void customerCaller_passesAddressDeletion() throws Exception {
        authenticate("ROLE_CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = dispatch("DELETE",
                "/customers/" + CUSTOMER_ID + "/addresses/" + UUID.randomUUID(), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void serviceProviderCaller_isForbiddenOnAddressDeletion() throws Exception {
        authenticate("ROLE_SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = dispatch("DELETE",
                "/customers/" + CUSTOMER_ID + "/addresses/" + UUID.randomUUID(), chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void supportAgentCaller_isForbiddenOnDeletionRequest() throws Exception {
        // Data deletion deliberately excludes SUPPORT_AGENT.
        authenticate("ROLE_SUPPORT_AGENT");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                dispatch("POST", "/customers/" + CUSTOMER_ID + "/deletion", chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void adminCaller_passesDeletionRequest() throws Exception {
        authenticate("ROLE_ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                dispatch("POST", "/customers/" + CUSTOMER_ID + "/deletion", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    /**
     * The filter runs inside the security chain, so a rule that matched the operational surface
     * would turn these probes into 401s. No rule may leak onto them.
     */
    @Test
    void actuatorHealth_passesWithoutAuthentication() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = dispatch("GET", "/actuator/health", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void otherOperationalPaths_passWithoutAuthentication() throws Exception {
        for (String path : List.of("/health/liveness", "/health/readiness", "/actuator/info",
                "/metrics", "/prometheus")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = dispatch("GET", path, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(200);
            verify(chain).doFilter(any(), any());
        }
    }
}
