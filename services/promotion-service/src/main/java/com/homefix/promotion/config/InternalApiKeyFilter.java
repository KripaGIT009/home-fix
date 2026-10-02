package com.homefix.promotion.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates service-to-service calls on {@code /internal/**} with a shared secret.
 *
 * <p>The Promotion Service's internal endpoints exist for other platform services (today the
 * Pricing Engine, which asks for a coupon's discount while quoting an estimate, Requirement 6.10).
 * Those calls carry no end-user token the Promotion Service could check ownership against, so
 * neither the JWT filter nor the RBAC filter can authorise them, and leaving them open would let
 * any caller probe every coupon and any customer's per-user usage. This is the mechanism the
 * Booking and Customer Services already use for their internal endpoints: a shared credential in
 * the {@code X-Internal-Api-Key} header, sourced from the {@code INTERNAL_API_KEY} environment
 * variable.
 *
 * <p>The key is compared in constant time, never logged, and has no default: if
 * {@code homefix.promotion.internal-api-key} is unset the filter rejects every internal request
 * rather than falling open. {@code /internal/**} is also not routed by the API Gateway, which only
 * forwards {@code /coupons/**} here, so it is reachable only from inside the cluster network. In
 * a cluster this should be paired with a network policy, and ultimately replaced by mutual TLS or a
 * workload identity; the review roadmap tracks that.
 *
 * <p>On success the request is authenticated as the calling service with the {@code ROLE_INTERNAL}
 * authority, which the security chain requires for {@code /internal/**}.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    /** Header carrying the shared internal credential. */
    public static final String HEADER = "X-Internal-Api-Key";

    /** Path prefix this filter guards. */
    public static final String INTERNAL_PREFIX = "/internal/";

    /** Authority granted to an authenticated internal caller. */
    public static final String INTERNAL_AUTHORITY = "ROLE_INTERNAL";

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyFilter.class);

    private final byte[] expectedKey;

    public InternalApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey == null || expectedKey.isBlank()
                ? null
                : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (expectedKey == null) {
            log.error("SECURITY rejected {} {}: homefix.promotion.internal-api-key is not configured",
                    request.getMethod(), request.getRequestURI());
            reject(response, "Internal API is not available");
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !constantTimeEquals(presented)) {
            // Never log the presented value; it is a credential.
            log.warn("SECURITY rejected {} {}: missing or invalid internal API key",
                    request.getMethod(), request.getRequestURI());
            reject(response, "Invalid internal credentials");
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "internal-service", null,
                        List.of(new SimpleGrantedAuthority(INTERNAL_AUTHORITY))));
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean constantTimeEquals(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"" + message + "\"}");
    }
}
