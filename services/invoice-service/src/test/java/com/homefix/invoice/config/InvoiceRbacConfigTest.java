package com.homefix.invoice.config;

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
 * {@link InvoiceRbacConfig} (Requirements 19.6, 19.7): a CUSTOMER must not reach the provider
 * earnings statement, a SERVICE_PROVIDER must not reach customer invoice history, finance staff
 * reach both, and an unauthenticated caller reaches neither.
 */
class InvoiceRbacConfigTest {

    private RbacEnforcementFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        RbacProperties props = new RbacProperties();
        new InvoiceRbacConfig(props).registerEndpointRoles();
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

    private static String providerStatementPath() {
        return "/invoices/providers/" + UUID.randomUUID() + "/statements/2026/1";
    }

    private static String customerHistoryPath() {
        return "/invoices/customers/" + UUID.randomUUID();
    }

    // ------------------------------------------------- provider earnings statement

    @Test
    void customer_isForbiddenFromProviderStatement() throws Exception {
        authenticateAs("CUSTOMER");

        assertRejectedWith(invoke("GET", providerStatementPath()), HttpStatus.FORBIDDEN);
    }

    @Test
    void serviceProvider_mayReadProviderStatement() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertPassedThrough(invoke("GET", providerStatementPath()));
    }

    @Test
    void financeAdmin_mayReadProviderStatement() throws Exception {
        authenticateAs("FINANCE_ADMIN");

        assertPassedThrough(invoke("GET", providerStatementPath()));
    }

    @Test
    void unauthenticatedProviderStatement_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", providerStatementPath()), HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------ customer invoice history

    @Test
    void serviceProvider_isForbiddenFromCustomerHistory() throws Exception {
        authenticateAs("SERVICE_PROVIDER");

        assertRejectedWith(invoke("GET", customerHistoryPath()), HttpStatus.FORBIDDEN);
    }

    @Test
    void customer_mayReadCustomerHistory() throws Exception {
        authenticateAs("CUSTOMER");

        assertPassedThrough(invoke("GET", customerHistoryPath()));
    }

    @Test
    void unauthenticatedCustomerHistory_isUnauthorized() throws Exception {
        assertRejectedWith(invoke("GET", customerHistoryPath()), HttpStatus.UNAUTHORIZED);
    }
}
