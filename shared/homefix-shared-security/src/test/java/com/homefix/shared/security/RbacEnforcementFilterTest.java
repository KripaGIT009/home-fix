package com.homefix.shared.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RbacEnforcementFilterTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        props.getEndpointRoles().put("GET /admin/**", List.of("ADMIN", "SUPER_ADMIN"));
        props.getEndpointRoles().put("POST /bookings", List.of("CUSTOMER"));
        props.getEndpointRoles().put("PUT /providers/**", List.of("SERVICE_PROVIDER"));
        filter = new RbacEnforcementFilter(props);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = List.of(roles).stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", null, authorities));
    }

    @Test
    void unprotectedEndpoint_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void protectedEndpoint_unauthenticated_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Authentication required");
    }

    @Test
    void protectedEndpoint_wrongRole_returns403() throws Exception {
        authenticateAs("CUSTOMER");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Insufficient role");
    }

    @Test
    void protectedEndpoint_correctRole_allowsAccess() throws Exception {
        authenticateAs("ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void protectedEndpoint_anyOfMultipleRoles_allowsAccess() throws Exception {
        authenticateAs("SUPER_ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/config");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void methodMismatch_onSamePath_isTreatedAsUnprotected() throws Exception {
        // Only "POST /bookings" is protected; a GET should not match and passes through.
        authenticateAs(); // no roles
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void exactMethodAndPath_enforcesRole() throws Exception {
        authenticateAs("SERVICE_PROVIDER");
        MockHttpServletRequest allowed = new MockHttpServletRequest("PUT", "/providers/42/profile");
        MockHttpServletResponse allowedResp = new MockHttpServletResponse();
        FilterChain allowedChain = mock(FilterChain.class);
        filter.doFilter(allowed, allowedResp, allowedChain);
        verify(allowedChain, times(1)).doFilter(allowed, allowedResp);

        // A customer hitting the same provider endpoint is forbidden.
        SecurityContextHolder.clearContext();
        authenticateAs("CUSTOMER");
        MockHttpServletRequest denied = new MockHttpServletRequest("PUT", "/providers/42/profile");
        MockHttpServletResponse deniedResp = new MockHttpServletResponse();
        FilterChain deniedChain = mock(FilterChain.class);
        filter.doFilter(denied, deniedResp, deniedChain);
        verify(deniedChain, never()).doFilter(denied, deniedResp);
        assertThat(deniedResp.getStatus()).isEqualTo(403);
    }

    @Test
    void unauthenticatedTokenObject_returns401() throws Exception {
        // Explicit not-authenticated token.
        UsernamePasswordAuthenticationToken unauth =
                new UsernamePasswordAuthenticationToken("user", null);
        unauth.setAuthenticated(false);
        SecurityContextHolder.getContext().setAuthentication(unauth);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
