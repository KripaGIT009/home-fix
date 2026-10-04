package com.homefix.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link InternalApiKeyFilter}: it fails closed when no key is configured, compares
 * the presented key, grants {@code ROLE_INTERNAL} only on a match, and leaves non-internal paths
 * untouched.
 */
class InternalApiKeyFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unconfiguredKey_rejectsEveryInternalRequest() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("  ");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/users/x/contact");
        request.addHeader(InternalApiKeyFilter.HEADER, "  ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).as("chain must not continue").isNull();
    }

    @Test
    void matchingKey_authenticatesAsInternalServiceForTheRequestOnly() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("k-123");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/users/x/contact");
        request.addHeader(InternalApiKeyFilter.HEADER, "k-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] authorityInsideChain = new String[1];

        filter.doFilter(request, response, (req, res) -> authorityInsideChain[0] =
                SecurityContextHolder.getContext().getAuthentication()
                        .getAuthorities().iterator().next().getAuthority());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authorityInsideChain[0]).isEqualTo(InternalApiKeyFilter.INTERNAL_AUTHORITY);
        // The service identity does not leak past the request.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonInternalPath_isNotFiltered() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("k-123");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/token/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Spring MVC decodes {@code /%69nternal/...} to {@code /internal/...} before routing it, so the
     * filter must decide on the decoded path; a raw-URI check would let the request skip the key.
     */
    @Test
    void encodedInternalPath_isStillGuarded() throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter("k-123");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setRequestURI("/%69nternal/users/x/contact");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).as("chain must not continue").isNull();
    }
}
