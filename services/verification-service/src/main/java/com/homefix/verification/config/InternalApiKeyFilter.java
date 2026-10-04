package com.homefix.verification.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates service-to-service calls on {@code /internal/**} with a shared secret.
 *
 * <p>The Verification Service's internal endpoints exist for other platform services (today the
 * Provider Service, which asks in one batch which dispatch candidates are {@code APPROVED},
 * Requirements 5.10 and 8.2). Those callers act on their own behalf and carry no end-user token, so
 * neither the JWT filter nor the RBAC filter can authorise them, and leaving them open would let
 * any caller enumerate which providers are approved. This is the same mechanism the Booking,
 * Customer and Provider Services use for their internal endpoints: a shared credential in the
 * {@code X-Internal-Api-Key} header, sourced from the {@code INTERNAL_API_KEY} environment
 * variable.
 *
 * <p>The key is compared in constant time, never logged, and has no default: if
 * {@code homefix.verification.internal-api-key} is unset the filter rejects every internal request
 * rather than falling open. {@code /internal/**} is also not routed by the API Gateway, which only
 * forwards {@code /verifications/**} and
 * {@code /admin/verifications/**} here, so it is reachable only from inside the cluster network. In
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

    /** Resolves the decoded, normalised path the dispatcher will route on. */
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final byte[] expectedKey;

    public InternalApiKeyFilter(String expectedKey) {
        this.expectedKey = expectedKey == null || expectedKey.isBlank()
                ? null
                : expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Skips only requests that will not reach an internal handler.
     *
     * <p>The decision is made on the decoded path within the application, not on the raw
     * {@link HttpServletRequest#getRequestURI() request URI}: Spring MVC decodes the URI before
     * matching handlers, so {@code /%69nternal/...} reaches {@code /internal/...} although its raw
     * form does not start with the prefix. The path is lower-cased so a case-insensitive matcher
     * elsewhere cannot open the same gap. The security chain also requires {@code ROLE_INTERNAL} on
     * {@code /internal/**}, but this filter must not depend on that rule to stay closed.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isInternalPath(request);
    }

    static boolean isInternalPath(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        return path != null && path.toLowerCase(Locale.ROOT).startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (expectedKey == null) {
            log.error("SECURITY rejected {} {}: homefix.verification.internal-api-key is not configured",
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
