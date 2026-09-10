package com.homefix.shared.security;

import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JwtValidationFilterTest {

    // 32+ byte secret required for HS256.
    private static final byte[] SECRET_BYTES =
            "homefix-test-signing-secret-key-0123456789".getBytes();
    private final SecretKey signingKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET_BYTES);
    private final SecretKey otherKey = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
            "a-completely-different-secret-key-9876543210".getBytes());

    private JwtValidationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtValidationFilter(signingKey);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String token(SecretKey key, List<String> roles, Date expiry) {
        return Jwts.builder()
                .subject("user-123")
                .claim(JwtValidationFilter.ROLES_CLAIM, roles)
                .issuedAt(new Date(System.currentTimeMillis() - 1000))
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    private Date inFuture() {
        return new Date(System.currentTimeMillis() + 60_000);
    }

    private Date inPast() {
        return new Date(System.currentTimeMillis() - 60_000);
    }

    @Test
    void validToken_populatesSecurityContextAndContinuesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization",
                "Bearer " + token(signingKey, List.of("CUSTOMER", "SERVICE_PROVIDER"), inFuture()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("user-123");
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        assertThat(authorities).containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_SERVICE_PROVIDER");
    }

    @Test
    void expiredToken_returns401AndDoesNotContinueChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization",
                "Bearer " + token(signingKey, List.of("CUSTOMER"), inPast()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("expired");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // Signed with a different key than the filter trusts.
        request.addHeader("Authorization",
                "Bearer " + token(otherKey, List.of("CUSTOMER"), inFuture()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void malformedToken_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void noAuthorizationHeader_passesThroughWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tokenWithoutRolesClaim_yieldsEmptyAuthorities() throws Exception {
        String noRoleToken = Jwts.builder()
                .subject("user-999")
                .expiration(inFuture())
                .signWith(signingKey)
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + noRoleToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void stringSecretConstructor_validatesTokenSignedWithSameSecret() throws Exception {
        JwtValidationFilter stringFilter = new JwtValidationFilter(new String(SECRET_BYTES));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization",
                "Bearer " + token(signingKey, List.of("ADMIN"), inFuture()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        stringFilter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
