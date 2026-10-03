package com.homefix.booking.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Drives the real {@link RbacEnforcementFilter} against the rules registered by
 * {@link BookingRbacConfig} (Requirements 19.6, 28.7): customers reach history and detail, a
 * provider reaches detail but not customer history, staff reach both, an unauthenticated caller
 * reaches neither, the Admin Portal's {@code /admin/bookings} list and cancel admit only the
 * operations staff (Requirement 19.2), and nothing else gains a rule.
 */
class BookingRbacConfigTest {

    private RbacEnforcementFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new BookingRbacConfig(props).registerEndpointRoles();
        filter = new RbacEnforcementFilter(props);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(), null, authorities));
    }

    private MockHttpServletResponse invoke(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private void assertPassedThrough(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain).doFilter(any(), any());
    }

    private void assertRejectedWith(MockHttpServletResponse response, HttpStatus status)
            throws Exception {
        assertThat(response.getStatus()).isEqualTo(status.value());
        verify(chain, never()).doFilter(any(), any());
    }

    private static String detailPath() {
        return "/bookings/" + UUID.randomUUID();
    }

    // ----- history -----------------------------------------------------------

    @Test
    void customer_mayReadHistory() throws Exception {
        authenticateAs("CUSTOMER");

        assertPassedThrough(invoke("GET", "/bookings/history"));
    }

    @Test
    void providerOnly_isForbiddenFromCustomerHistory() throws Exception {
        // Ordering check too: history must match its own rule, not fall through to the
        // broader detail rule that does admit providers.
        authenticateAs("SERVICE_PROVIDER");

        assertRejectedWith(invoke("GET", "/bookings/history"), HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN", "SUPPORT_AGENT", "DISPATCHER"})
    void staff_mayReadHistory(String role) throws Exception {
        authenticateAs(role);

        assertPassedThrough(invoke("GET", "/bookings/history"));
    }

    @Test
    void unauthenticatedHistory_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", "/bookings/history"), HttpStatus.UNAUTHORIZED);
    }

    // ----- detail ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "ADMIN", "SUPPORT_AGENT", "DISPATCHER"})
    void customerProviderAndStaff_mayReadDetail(String role) throws Exception {
        authenticateAs(role);

        assertPassedThrough(invoke("GET", detailPath()));
    }

    @Test
    void detailByReference_isGovernedByTheSameRule() throws Exception {
        assertRejectedWith(invoke("GET", "/bookings/HFX-20261002-ABC123"), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unauthenticatedDetail_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", detailPath()), HttpStatus.UNAUTHORIZED);
    }

    // ----- admin portal --------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT", "DISPATCHER"})
    void operationsStaff_mayListBookings(String role) throws Exception {
        authenticateAs(role);

        assertPassedThrough(invoke("GET", "/admin/bookings"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT", "DISPATCHER"})
    void operationsStaff_mayCancelBookings(String role) throws Exception {
        authenticateAs(role);

        assertPassedThrough(invoke("POST", "/admin/bookings/" + UUID.randomUUID() + "/cancel"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "FINANCE_ADMIN"})
    void othersAreForbiddenFromTheAdminList(String role) throws Exception {
        authenticateAs(role);

        assertRejectedWith(invoke("GET", "/admin/bookings"), HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "FINANCE_ADMIN"})
    void othersAreForbiddenFromTheAdminCancel(String role) throws Exception {
        authenticateAs(role);

        assertRejectedWith(invoke("POST", "/admin/bookings/HFX-20261002-ABC123/cancel"),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedAdminList_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", "/admin/bookings"), HttpStatus.UNAUTHORIZED);
    }

    // ----- tenant portal (Requirement MT-10.1, MT-10.3) ------------------------

    @Test
    void tenantAdmin_mayReachTheQueueListAndAssignment() throws Exception {
        authenticateAs("TENANT_ADMIN");
        assertPassedThrough(invoke("GET", "/tenant/bookings/queue"));

        chain = mock(FilterChain.class);
        authenticateAs("TENANT_ADMIN");
        assertPassedThrough(invoke("GET", "/tenant/bookings"));

        chain = mock(FilterChain.class);
        authenticateAs("TENANT_ADMIN");
        assertPassedThrough(invoke("POST", "/tenant/bookings/" + UUID.randomUUID() + "/assignment"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "ADMIN", "SUPER_ADMIN", "SUPPORT_AGENT", "DISPATCHER"})
    void everyoneElse_isForbiddenFromTheTenantQueue(String role) throws Exception {
        authenticateAs(role);

        assertRejectedWith(invoke("GET", "/tenant/bookings/queue"), HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "ADMIN"})
    void everyoneElse_isForbiddenFromTenantAssignment(String role) throws Exception {
        authenticateAs(role);

        assertRejectedWith(invoke("POST", "/tenant/bookings/HFX-20261002-ABC123/assignment"),
                HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/bookings", "/bookings/history"})
    void tenantAdmin_isForbiddenFromPlatformAndCustomerPaths(String path) throws Exception {
        authenticateAs("TENANT_ADMIN");

        assertRejectedWith(invoke("GET", path), HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedTenantQueue_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", "/tenant/bookings/queue"), HttpStatus.UNAUTHORIZED);
    }

    // ----- paths deliberately left without a rule ------------------------------

    @Test
    void assignmentAnswers_gainNoRule() throws Exception {
        // Ownership (assigned provider only) is ProviderAssignmentService's, as for other commands.
        authenticateAs("SERVICE_PROVIDER");

        assertPassedThrough(invoke("POST", "/bookings/HFX-20261002-ABC123/assignment/acceptance"));
    }

    @Test
    void commandEndpoints_gainNoRule() throws Exception {
        // Unchanged "authenticated only": the filter passes them through to the chain, which
        // still requires an authenticated principal.
        authenticateAs("SERVICE_PROVIDER");

        assertPassedThrough(invoke("POST", "/bookings/HFX-20261002-ABC123/confirmation"));
    }

    @Test
    void operationalSurface_staysUnruled() throws Exception {
        assertPassedThrough(invoke("GET", "/actuator/health"));
    }
}
