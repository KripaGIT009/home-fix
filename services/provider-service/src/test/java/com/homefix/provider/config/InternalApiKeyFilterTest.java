package com.homefix.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link InternalApiKeyFilter}: it guards {@code /internal/**} however the path is
 * spelled on the wire, grants {@code ROLE_INTERNAL} only on a matching key, and leaves every other
 * path to the JWT filter.
 */
class InternalApiKeyFilterTest {

    private static final String KEY = "correct-internal-key";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingKey_authenticatesTheCallingServiceAndContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/providers/eligible");
        request.addHeader(InternalApiKeyFilter.HEADER, KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void missingKey_isRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/providers/eligible");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_AUTH_FAILED");
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * Spring MVC decodes {@code /%69nternal/...} to {@code /internal/...} before routing it, so the
     * filter must decide on the decoded path; a raw-URI check would let the request skip the key.
     */
    @Test
    void encodedInternalPath_isStillGuarded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/%69nternal/providers/eligible");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void nonInternalPath_isLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/providers/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // No key configured and none presented: a user-facing path must still reach the JWT filter.
        new InternalApiKeyFilter(null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }
}
