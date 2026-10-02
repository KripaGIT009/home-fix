package com.homefix.payment.config;

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
 * {@link PaymentRbacConfig} (Requirements 19.6, 19.7).
 *
 * <p>The most important case here is
 * {@link #gatewayCallback_withNoAuthentication_passesThrough()}: the RBAC filter is installed
 * <em>inside</em> the security chain, so it also runs for {@code permitAll()} paths. A catch-all
 * {@code POST /payments/**} rule would therefore 401 every (unauthenticated, HMAC-verified) gateway
 * callback. That test is the regression guard against reintroducing such a rule.
 */
class PaymentRbacConfigTest {

    private RbacEnforcementFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new PaymentRbacConfig(props).registerEndpointRoles();
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

    // ------------------------------------------------------------------- refunds

    @Test
    void customer_isForbiddenFromRefunds() throws Exception {
        authenticateAs("CUSTOMER");

        assertRejectedWith(invoke("POST", "/payments/" + UUID.randomUUID() + "/refunds"),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void financeAdmin_mayRefund() throws Exception {
        authenticateAs("FINANCE_ADMIN");

        assertPassedThrough(invoke("POST", "/payments/" + UUID.randomUUID() + "/refunds"));
    }

    // ------------------------------------------------------- refund reconciliation

    @Test
    void customer_isForbiddenFromReconcilingRefunds() throws Exception {
        authenticateAs("CUSTOMER");

        assertRejectedWith(invoke("POST", "/payments/" + UUID.randomUUID() + "/refunds/"
                + UUID.randomUUID() + "/reconcile"), HttpStatus.FORBIDDEN);
    }

    @Test
    void supportAgent_isForbiddenFromReconcilingRefunds() throws Exception {
        authenticateAs("SUPPORT_AGENT");

        assertRejectedWith(invoke("POST", "/payments/" + UUID.randomUUID() + "/refunds/"
                + UUID.randomUUID() + "/reconcile"), HttpStatus.FORBIDDEN);
    }

    @Test
    void financeAdmin_mayReconcileRefunds() throws Exception {
        authenticateAs("FINANCE_ADMIN");

        assertPassedThrough(invoke("POST", "/payments/" + UUID.randomUUID() + "/refunds/"
                + UUID.randomUUID() + "/reconcile"));
    }

    // --------------------------------------------------------------- settlements

    @Test
    void customer_isForbiddenFromSettlements() throws Exception {
        authenticateAs("CUSTOMER");

        assertRejectedWith(invoke("POST", "/payments/settlements"), HttpStatus.FORBIDDEN);
    }

    @Test
    void financeAdmin_maySettle() throws Exception {
        authenticateAs("FINANCE_ADMIN");

        assertPassedThrough(invoke("POST", "/payments/settlements"));
    }

    @Test
    void unauthenticatedSettlement_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("POST", "/payments/settlements"), HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ initiate

    @Test
    void customer_mayInitiatePayment() throws Exception {
        authenticateAs("CUSTOMER");

        assertPassedThrough(invoke("POST", "/payments"));
    }

    // ------------------------------------------------------------ admin portal

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN"})
    void financeTier_mayListPayments(String role) throws Exception {
        authenticateAs(role);

        assertPassedThrough(invoke("GET", "/admin/payments"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPER_ADMIN", "FINANCE_ADMIN"})
    void financeTier_mayRefundFromThePortal(String role) throws Exception {
        authenticateAs(role);

        assertPassedThrough(invoke("POST", "/admin/payments/" + UUID.randomUUID() + "/refund"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "DISPATCHER"})
    void othersAreForbiddenFromTheAdminList(String role) throws Exception {
        authenticateAs(role);

        assertRejectedWith(invoke("GET", "/admin/payments"), HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "SERVICE_PROVIDER", "SUPPORT_AGENT", "DISPATCHER"})
    void othersAreForbiddenFromThePortalRefund(String role) throws Exception {
        authenticateAs(role);

        assertRejectedWith(invoke("POST", "/admin/payments/" + UUID.randomUUID() + "/refund"),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void unauthenticatedAdminList_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", "/admin/payments"), HttpStatus.UNAUTHORIZED);
    }

    // --------------------------------------------------- public gateway callback

    /**
     * Regression guard: the public, HMAC-verified gateway callback must remain rule-free, otherwise
     * the RBAC filter (which runs inside the chain even for {@code permitAll()} paths) rejects every
     * callback with a 401 and payments are stranded in {@code PENDING}.
     */
    @Test
    void gatewayCallback_withNoAuthentication_passesThrough() throws Exception {
        assertPassedThrough(invoke("POST", "/payments/callbacks/" + UUID.randomUUID()));
    }
}
