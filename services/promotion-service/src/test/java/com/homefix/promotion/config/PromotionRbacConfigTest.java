package com.homefix.promotion.config;

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
 * {@link PromotionRbacConfig}, so the assertions cover effective runtime behaviour (pattern
 * ordering, method matching, 401 vs 403) rather than the contents of a map.
 *
 * <p>{@link #customerPassesThroughOnRedeem()} and {@link #customerPassesThroughOnValidate()} are
 * the ordering guard: the Admin-only {@code POST /coupons} create rule must never swallow the
 * customer checkout paths.
 */
class PromotionRbacConfigTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new PromotionRbacConfig(props).registerEndpointRoles();
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

    // ------------------------------------------------------------------ Admin Portal surface

    private static String[][] adminPortalCalls() {
        return new String[][] {
                {"GET", "/admin/coupons"},
                {"POST", "/admin/coupons"},
                {"PATCH", "/admin/coupons/" + UUID.randomUUID() + "/deactivate"}};
    }

    @Test
    void adminTierPassesThroughOnTheAdminPortalCouponSurface() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN")) {
            authenticateAs(role);
            for (String[] call : adminPortalCalls()) {
                FilterChain chain = mock(FilterChain.class);

                MockHttpServletResponse response = invoke(call[0], call[1], chain);

                assertThat(response.getStatus()).as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.OK.value());
                verify(chain, times(1)).doFilter(any(), any());
            }
        }
    }

    @Test
    void otherRolesAreForbiddenOnTheAdminPortalCouponSurface() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "FINANCE_ADMIN")) {
            authenticateAs(role);
            for (String[] call : adminPortalCalls()) {
                FilterChain chain = mock(FilterChain.class);

                assertThat(invoke(call[0], call[1], chain).getStatus())
                        .as(role + " " + call[0] + " " + call[1])
                        .isEqualTo(HttpStatus.FORBIDDEN.value());
                verify(chain, never()).doFilter(any(), any());
            }
        }
    }

    @Test
    void unauthenticatedAdminPortalCouponListIsUnauthorized() throws Exception {
        assertThat(invoke("GET", "/admin/coupons", mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    /** The new /admin rules must not loosen the existing customer-facing coupon admin rules. */
    @Test
    void existingCouponCreateAndDeactivateStayAdminOnly() throws Exception {
        for (String role : List.of("CUSTOMER", "SUPPORT_AGENT")) {
            authenticateAs(role);
            assertThat(invoke("POST", "/coupons", mock(FilterChain.class)).getStatus())
                    .as(role).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(invoke("POST", "/coupons/" + UUID.randomUUID() + "/deactivate",
                    mock(FilterChain.class)).getStatus())
                    .as(role).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    // ------------------------------------------------------------------ admin surface

    @Test
    void customerIsForbiddenOnCouponCreate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void customerIsForbiddenOnDeactivate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/coupons/" + UUID.randomUUID() + "/deactivate", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void adminPassesThroughOnCouponCreate() throws Exception {
        authenticateAs("ADMIN");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void adminPassesThroughOnDeactivateAndActivate() throws Exception {
        authenticateAs("ADMIN");
        UUID couponId = UUID.randomUUID();

        FilterChain deactivateChain = mock(FilterChain.class);
        assertThat(invoke("POST", "/coupons/" + couponId + "/deactivate", deactivateChain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(deactivateChain, times(1)).doFilter(any(), any());

        FilterChain activateChain = mock(FilterChain.class);
        assertThat(invoke("POST", "/coupons/" + couponId + "/activate", activateChain).getStatus())
                .isEqualTo(HttpStatus.OK.value());
        verify(activateChain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedCouponCreateIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void supportAgentIsForbiddenOnCouponCreate() throws Exception {
        authenticateAs("SUPPORT_AGENT");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    // ------------------------------------------------------------------ customer checkout flow

    @Test
    void customerPassesThroughOnRedeem() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons/redeem", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void customerPassesThroughOnValidate() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons/validate", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void customerPassesThroughOnCancel() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("POST", "/coupons/cancel", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void customerAndProviderMayReadCouponsById() throws Exception {
        UUID couponId = UUID.randomUUID();

        authenticateAs("CUSTOMER");
        assertThat(invoke("GET", "/coupons/" + couponId, mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.OK.value());

        authenticateAs("SERVICE_PROVIDER");
        assertThat(invoke("GET", "/coupons/" + couponId, mock(FilterChain.class)).getStatus())
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void codeLookupIsMatchedByItsOwnRuleNotTheIdRule() throws Exception {
        authenticateAs("SERVICE_PROVIDER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/coupons/code/SAVE50", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void unauthenticatedReadIsUnauthorized() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/coupons/" + UUID.randomUUID(), chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        verify(chain, never()).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ internal surface

    /**
     * {@code /internal/**} is authorised by the shared service credential, not by roles. The
     * caller {@code InternalApiKeyFilter} authenticates holds only {@code ROLE_INTERNAL}, so any
     * role rule covering the path would lock the Pricing Engine out of coupon quotes.
     */
    @Test
    void internalCouponQuoteHasNoRoleRuleSoTheServiceCallerPassesThrough() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("internal-service", null,
                        List.of(new SimpleGrantedAuthority(InternalApiKeyFilter.INTERNAL_AUTHORITY))));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/internal/coupons/SAVE50/quote", chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain, times(1)).doFilter(any(), any());
    }

    // ------------------------------------------------------------------ public surfaces

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
