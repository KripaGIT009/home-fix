package com.homefix.rating.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
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
 * Drives the real {@link RbacEnforcementFilter} against the rule map that
 * {@link RatingRbacConfig} registers, so the assertions are about the deployed decision and not
 * merely the shape of a map. The service's controller tests use {@code standaloneSetup}, which does
 * not run the security filter chain, so this is the only place the rules are actually exercised.
 */
class RatingRbacConfigTest {

    private RbacEnforcementFilter filter;

    @BeforeEach
    void setUp() {
        RbacProperties properties = new RbacProperties();
        new RatingRbacConfig(properties).registerEndpointRoles();
        filter = new RbacEnforcementFilter(properties);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        List<String> authorities = List.of(roles).stream().map(r -> "ROLE_" + r).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID().toString(), "n/a",
                AuthorityUtils.createAuthorityList(authorities.toArray(String[]::new))));
    }

    private MockHttpServletResponse invoke(String method, String path, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    // ---- Moderation is staff-only (Requirement 15.5, 15.9) -------------------------------------

    @Test
    void customerIsDeniedReviewApproval() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/reviews/" + UUID.randomUUID() + "/approval", chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void customerIsDeniedReviewRemoval() throws Exception {
        authenticateAs("CUSTOMER");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/reviews/" + UUID.randomUUID() + "/removal", chain);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void supportAgentMayApproveAndRemove() throws Exception {
        authenticateAs("SUPPORT_AGENT");
        FilterChain approvalChain = mock(FilterChain.class);
        FilterChain removalChain = mock(FilterChain.class);

        MockHttpServletResponse approval =
                invoke("POST", "/reviews/" + UUID.randomUUID() + "/approval", approvalChain);
        MockHttpServletResponse removal =
                invoke("POST", "/reviews/" + UUID.randomUUID() + "/removal", removalChain);

        assertThat(approval.getStatus()).isEqualTo(200);
        assertThat(removal.getStatus()).isEqualTo(200);
        verify(approvalChain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(removalChain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adminMayApprove() throws Exception {
        authenticateAs("ADMIN");

        MockHttpServletResponse response = invoke(
                "POST", "/reviews/" + UUID.randomUUID() + "/approval", mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void unauthenticatedModerationIsRejectedWith401() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response =
                invoke("POST", "/reviews/" + UUID.randomUUID() + "/removal", chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ---- Submission roles ----------------------------------------------------------------------

    @Test
    void customerMaySubmitCustomerReview() throws Exception {
        authenticateAs("CUSTOMER");

        assertThat(invoke("POST", "/reviews", mock(FilterChain.class)).getStatus()).isEqualTo(200);
    }

    @Test
    void customerMayNotSubmitProviderRatingOfCustomer() throws Exception {
        authenticateAs("CUSTOMER");

        assertThat(invoke("POST", "/reviews/customer", mock(FilterChain.class)).getStatus())
                .isEqualTo(403);
    }

    @Test
    void serviceProviderMaySubmitProviderRatingButNotCustomerReview() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertThat(invoke("POST", "/reviews/customer", mock(FilterChain.class)).getStatus())
                .isEqualTo(200);
        assertThat(invoke("POST", "/reviews", mock(FilterChain.class)).getStatus()).isEqualTo(403);
    }

    // ---- Admin Portal moderation (Requirement 19.2) --------------------------------------------

    private static String moderatePath() {
        return "/admin/reviews/" + UUID.randomUUID() + "/moderate";
    }

    @Test
    void moderationTierMayListAndModerateFromTheAdminPortal() throws Exception {
        for (String role : List.of("ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT")) {
            authenticateAs(role);
            for (String[] call : new String[][] {
                    {"GET", "/admin/reviews"}, {"POST", moderatePath()}}) {
                FilterChain chain = mock(FilterChain.class);

                assertThat(invoke(call[0], call[1], chain).getStatus())
                        .as(role + " " + call[0] + " " + call[1]).isEqualTo(200);
                verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
            }
        }
    }

    @Test
    void otherRolesAreDeniedTheAdminPortalModerationSurface() throws Exception {
        for (String role : List.of("CUSTOMER", "SERVICE_PROVIDER", "FINANCE_ADMIN", "DISPATCHER")) {
            authenticateAs(role);
            for (String[] call : new String[][] {
                    {"GET", "/admin/reviews"}, {"POST", moderatePath()}}) {
                assertThat(invoke(call[0], call[1], mock(FilterChain.class)).getStatus())
                        .as(role + " " + call[0] + " " + call[1]).isEqualTo(403);
            }
        }
    }

    @Test
    void unauthenticatedAdminReviewListIsUnauthorized() throws Exception {
        SecurityContextHolder.clearContext();

        assertThat(invoke("GET", "/admin/reviews", mock(FilterChain.class)).getStatus())
                .isEqualTo(401);
    }

    // ---- Public surface must stay unruled ------------------------------------------------------

    @Test
    void actuatorSurfaceIsNotCaughtByAnyRule() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse response = invoke("GET", "/actuator/health", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
