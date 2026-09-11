package com.homefix.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.homefix.shared.security.RbacEnforcementFilter;
import com.homefix.shared.security.RbacProperties;

import jakarta.servlet.FilterChain;

/**
 * Drives the real {@link RbacEnforcementFilter} over the rules registered by
 * {@link CatalogRbacConfig}, so the assertions cover effective runtime behaviour (pattern ordering,
 * method matching, 401 vs 403) rather than the contents of a map.
 *
 * <p>{@link #publicCatalogListingPassesThroughWithNoAuthentication()} is the regression guard for
 * the public customer listing: the filter runs inside the security chain even for
 * {@code permitAll()} paths, so any rule accidentally covering {@code GET /catalog/**} would turn
 * anonymous catalog browsing into a 401.
 */
class CatalogRbacConfigTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new CatalogRbacConfig(props).registerEndpointRoles();
        filter = new RbacEnforcementFilter(props);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ helpers

    private void authenticateAs(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(),
                        null,
                        List.of(roles).stream()
                                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                                .toList()));
    }

    private MockHttpServletResponse invoke(String method, String path, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // ------------------------------------------------------------------ admin surface

    @Test
    void customerIsForbiddenOnAdminCategoryCreate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/admin/catalog/categories", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminPassesThroughOnAdminCategoryCreate() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/admin/catalog/categories", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void superAdminPassesThroughOnAdminCategoryCreate() throws Exception {
        authenticateAs("SUPER_ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/admin/catalog/categories", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedDeleteIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("DELETE", "/admin/catalog/categories/" + UUID.randomUUID(), chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminGetAndPutAreForbiddenForNonStaff() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertThat(invoke("GET", "/admin/catalog/categories", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(invoke("PUT", "/admin/catalog/subcategories/" + UUID.randomUUID(),
                mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ------------------------------------------------------------------ public surfaces

    @Test
    void publicCatalogListingPassesThroughWithNoAuthentication() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/catalog/categories", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void healthAndMetricsSurfacePassesThroughWithNoAuthentication() throws Exception {
        for (String path : List.of("/health/liveness", "/actuator/health", "/metrics", "/prometheus")) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = invoke("GET", path, chain);

            assertThat(response.getStatus()).as(path).isEqualTo(HttpStatus.OK.value());
            verify(chain, times(1)).doFilter(any(), any());
        }
    }
}
