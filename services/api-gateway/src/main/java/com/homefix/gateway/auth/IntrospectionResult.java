package com.homefix.gateway.auth;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of a JWT introspection call against the Auth Service {@code GET /auth/introspect}
 * (Requirement 23.1). Carries only the claims the gateway needs for authorization and rate-limit
 * keying — never PII.
 *
 * @param active    whether the token is valid and unexpired
 * @param subject   the user id claim ({@code sub}); used as the per-user rate-limit key
 * @param roles     the user's role claims; used to resolve the applicable rate limit
 * @param expiresAt the token's own expiry ({@code exp}), or {@code null} when the Auth Service did
 *                  not report one; bounds how long {@link CachingTokenIntrospector} may reuse an
 *                  active result
 */
public record IntrospectionResult(boolean active, String subject, List<String> roles, Instant expiresAt) {

    public static IntrospectionResult inactive() {
        return new IntrospectionResult(false, null, List.of(), null);
    }

    public IntrospectionResult {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** An active or inactive result with no known expiry; such a result is never cached. */
    public IntrospectionResult(boolean active, String subject, List<String> roles) {
        this(active, subject, roles, null);
    }
}
