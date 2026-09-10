package com.homefix.gateway.auth;

import reactor.core.publisher.Mono;

/**
 * Port for validating a bearer token by calling the Auth Service {@code GET /auth/introspect}
 * (Requirement 23.1). Kept as an interface so the gateway's auth logic can be tested with a stub
 * and the real HTTP adapter can be swapped without touching the filter.
 */
public interface TokenIntrospector {

    /**
     * Introspects the supplied bearer token (raw JWT, without the {@code Bearer } prefix).
     * Never throws for an invalid token — a transport or validation failure resolves to
     * {@link IntrospectionResult#inactive()} so the caller can reject with 401.
     */
    Mono<IntrospectionResult> introspect(String token);
}
