package com.homefix.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Tests for the shared-credential guard on {@code /internal/**}.
 *
 * <p>These endpoints drive booking state transitions on behalf of the Dispatch Engine, which carries
 * no end-user token, so the filter is the only thing standing between them and an anonymous caller.
 * The cases that matter are that it never falls open when unconfigured, and that it leaves every
 * other path alone.
 */
class InternalApiKeyFilterTest {

    private static final String KEY = "correct-internal-key";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest internalRequest(String presentedKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/internal/bookings/b2e7d410-3a65-4c98-9f12-7d4e6a8b0c55/provider-accepted");
        if (presentedKey != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, presentedKey);
        }
        return request;
    }

    @Test
    void correctKey_authenticatesTheCallingServiceAndContinues() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(internalRequest(KEY), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void wrongKey_isRejectedAndTheChainIsNotInvoked() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(internalRequest("not-the-key"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INTERNAL_AUTH_FAILED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingHeader_isRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new InternalApiKeyFilter(KEY).doFilter(internalRequest(null), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    /**
     * The case worth being strict about: an unset key must close the endpoint, not open it. A filter
     * that falls open when misconfigured would expose booking transitions to anyone.
     */
    @Test
    void unconfiguredKey_rejectsEveryInternalRequestRatherThanFallingOpen() throws Exception {
        for (String configured : new String[] {null, "", "   "}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            new InternalApiKeyFilter(configured).doFilter(internalRequest(KEY), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Test
    void nonInternalPaths_areLeftAlone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/bookings");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // No key configured and none presented: a customer-facing path must still pass through to
        // the JWT filter rather than being rejected by this one.
        new InternalApiKeyFilter(null).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
