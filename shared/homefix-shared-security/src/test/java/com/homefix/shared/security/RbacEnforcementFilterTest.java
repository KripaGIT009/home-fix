package com.homefix.shared.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    // -------------------------------------------------------------------------
    // Path-normalisation bypass regressions (CODEBASE_REVIEW 8.2: "RBAC Ant matching on raw URI")
    //
    // Every request below reaches the protected handler in a real Spring MVC application, and
    // every one of them used to pass through this filter unmatched because the rule was compared
    // against the raw request URI. Each is now either matched against its normalised path (so the
    // role check runs) or refused outright with 400.
    // -------------------------------------------------------------------------

    private MockHttpServletResponse invoke(MockHttpServletRequest request, FilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private MockHttpServletResponse invoke(String method, String rawUri, FilterChain chain)
            throws Exception {
        return invoke(new MockHttpServletRequest(method, rawUri), chain);
    }

    @ParameterizedTest(name = "{0} is refused with 400")
    @ValueSource(strings = {
            "/admin;x/users",                 // path parameter on a segment
            "/admin;jsessionid=abc123/users", // session-id path parameter
            "/admin/users;jsessionid=abc123", // trailing path parameter
            "/admin%3Bx/users",               // encoded semicolon
            "/admin%2Fusers",                 // encoded slash
            "/admin%2fusers",                 // encoded slash, lower case
            "/admin%5Cusers",                 // encoded backslash
            "/admin\\users",                  // raw backslash
            "//admin/users",                  // empty leading segment
            "/admin//users",                  // empty inner segment
            "/admin/users//",                 // empty trailing segment
            "/./admin/users",                 // current-directory segment
            "/admin/./users",
            "/public/../admin/users",         // parent-directory segment
            "/admin/users/..",
            "/%2e%2e/admin/users",            // encoded parent segment
            "/%2E/admin/users",               // encoded current segment
            "/%2561dmin/users",               // double encoding (%25 = '%')
            "/admin%00/users"                 // encoded NUL
    })
    void ambiguousPath_isRefusedBeforeAnyRuleIsEvaluated(String rawUri) throws Exception {
        authenticateAs("ADMIN"); // even the right role must not get through an ambiguous path
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", rawUri, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Malformed request path");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void malformedPercentEncoding_isRefusedWith400() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/admin/%zzusers", chain);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void percentEncodedLetters_areDecodedBeforeMatching() throws Exception {
        // %61 = 'a'. Spring MVC decodes this to /admin/users and dispatches to the admin handler.
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/%61dmin/users", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void percentEncodedLetters_stillAdmitTheRightRole() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/%61dmin/users", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void trailingSlash_isGovernedByTheSameRule() throws Exception {
        // "POST /bookings" is the rule; "/bookings/" used to fall through unmatched.
        authenticateAs("SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/bookings/", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void trailingSlash_unauthenticated_returns401() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/bookings/", chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void trailingSlash_admitsTheRightRole() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        assertThat(invoke("POST", "/bookings/", chain).getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void upperCasePath_isStillGoverned() throws Exception {
        // MVC is case-sensitive today, so this would 404; matching case-insensitively is a
        // belt-and-braces guard should a service ever enable case-insensitive handler matching.
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/ADMIN/Users", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void servletContextPath_isStrippedBeforeMatching() throws Exception {
        // Under server.servlet.context-path=/api the raw URI is /api/admin/users and never
        // matched "/admin/**", while the handler mapping saw /admin/users.
        authenticateAs("CUSTOMER");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setContextPath("/api");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke(request, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void servletContextPath_rightRolePassesThrough() throws Exception {
        authenticateAs("ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setContextPath("/api");
        FilterChain chain = mock(FilterChain.class);

        assertThat(invoke(request, chain).getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void headRequest_isGovernedByTheGetRule() throws Exception {
        // Spring MVC serves HEAD by invoking the GET handler, so a GET-only rule must cover it.
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("HEAD", "/admin/users", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void headRequest_unauthenticated_returns401() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        assertThat(invoke("HEAD", "/admin/users", chain).getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void headRequest_onUnruledPath_stillPassesThrough() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        assertThat(invoke("HEAD", "/public/health", chain).getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void headDoesNotInheritNonGetRules() throws Exception {
        // Only "POST /bookings" exists for that path; HEAD maps to GET, not POST.
        FilterChain chain = mock(FilterChain.class);

        assertThat(invoke("HEAD", "/bookings", chain).getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void ruleMethodIsCaseInsensitive() throws Exception {
        RbacProperties props = new RbacProperties();
        props.getEndpointRoles().put("get /reports/**", List.of("ADMIN"));
        RbacEnforcementFilter lowerCaseRuleFilter = new RbacEnforcementFilter(props);
        authenticateAs("CUSTOMER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        lowerCaseRuleFilter.doFilter(new MockHttpServletRequest("GET", "/reports/daily"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void methodlessRule_matchesAnyMethodOnTheNormalisedPath() throws Exception {
        RbacProperties props = new RbacProperties();
        props.getEndpointRoles().put("/internal/**", List.of("INTERNAL"));
        RbacEnforcementFilter methodlessFilter = new RbacEnforcementFilter(props);
        authenticateAs("CUSTOMER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        methodlessFilter.doFilter(new MockHttpServletRequest("DELETE", "/%69nternal/x/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void passingRequest_isTheOriginalObjectNotAFirewallWrapper() throws Exception {
        authenticateAs("ADMIN");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }
}
