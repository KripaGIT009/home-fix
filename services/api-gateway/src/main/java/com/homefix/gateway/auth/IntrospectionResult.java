package com.homefix.gateway.auth;

import java.util.List;

/**
 * Outcome of a JWT introspection call against the Auth Service {@code GET /auth/introspect}
 * (Requirement 23.1). Carries only the claims the gateway needs for authorization and rate-limit
 * keying — never PII.
 *
 * @param active  whether the token is valid and unexpired
 * @param subject the user id claim ({@code sub}); used as the per-user rate-limit key
 * @param roles   the user's role claims; used to resolve the applicable rate limit
 */
public record IntrospectionResult(boolean active, String subject, List<String> roles) {

    public static IntrospectionResult inactive() {
        return new IntrospectionResult(false, null, List.of());
    }

    public IntrospectionResult {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
