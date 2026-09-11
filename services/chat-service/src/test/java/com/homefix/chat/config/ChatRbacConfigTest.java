package com.homefix.chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
 * Drives the real {@link RbacEnforcementFilter} over the rule map that {@link ChatRbacConfig}
 * registers, so these assertions describe the deployed authorization decision rather than the shape
 * of a map. The service's controller tests invoke the controller directly and never run the
 * security filter chain, so this is the only place the rules themselves are exercised.
 */
class ChatRbacConfigTest {

    private static final UUID BOOKING = UUID.randomUUID();
    private static final String MESSAGES = "/chat/channels/" + BOOKING + "/messages";

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new ChatRbacConfig(properties).registerEndpointRoles();
        filter = new RbacEnforcementFilter(properties);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), "n/a",
                AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private MockHttpServletResponse invoke(String method, String path, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // ----- Participants are admitted (the fine-grained check still runs in ChatService) ---------

    @Test
    void customerMaySendAndReadOnAChannel() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain send = mock(FilterChain.class);
        FilterChain read = mock(FilterChain.class);

        assertThat(invoke("POST", MESSAGES, send).getStatus()).isEqualTo(200);
        assertThat(invoke("GET", MESSAGES, read).getStatus()).isEqualTo(200);
        verify(send, times(1)).doFilter(any(), any());
        verify(read, times(1)).doFilter(any(), any());
    }

    @Test
    void serviceProviderMaySendAndReadOnAChannel() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertThat(invoke("POST", MESSAGES, mock(FilterChain.class)).getStatus()).isEqualTo(200);
        assertThat(invoke("GET", MESSAGES, mock(FilterChain.class)).getStatus()).isEqualTo(200);
    }

    // ----- Non-participant roles are refused at the filter --------------------------------------

    @Test
    void adminIsForbiddenFromReadingAChannel() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", MESSAGES, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void supportAgentIsForbiddenFromSendingOnAChannel() throws Exception {
        authenticateAs("SUPPORT_AGENT");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", MESSAGES, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void dispatcherIsForbiddenFromBothMessagePaths() throws Exception {
        authenticateAs("DISPATCHER");

        assertThat(invoke("POST", MESSAGES, mock(FilterChain.class)).getStatus()).isEqualTo(403);
        assertThat(invoke("GET", MESSAGES, mock(FilterChain.class)).getStatus()).isEqualTo(403);
    }

    @Test
    void unauthenticatedCallerIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", MESSAGES, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }

    // ----- Surfaces that must stay rule-free ----------------------------------------------------

    @Test
    void websocketHandshakePassesThroughWithNoAuthentication() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/ws/chat", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void healthAndMetricsSurfacePassesThroughWithNoAuthentication() throws Exception {
        for (String path : new String[] {"/actuator/health", "/health/liveness", "/metrics",
                "/prometheus"}) {
            FilterChain chain = mock(FilterChain.class);

            assertThat(invoke("GET", path, chain).getStatus()).as(path).isEqualTo(200);
            verify(chain, times(1)).doFilter(any(), any());
        }
    }
}
