package com.homefix.gateway.auth;

import com.homefix.gateway.config.GatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@link TokenIntrospector} that calls the Auth Service {@code GET /auth/introspect} over HTTP
 * (Requirement 23.1). The Auth Service returns a claims map: {@code {"active":true,"sub":"...",
 * "roles":["CUSTOMER"]}} for a valid token, or {@code {"active":false}} otherwise (see
 * {@code IntrospectController} in auth-service).
 *
 * <p>Any transport error is mapped to an inactive result so a downstream failure cannot be used to
 * bypass authentication; the gateway simply returns 401.
 *
 * <p>The whole exchange is capped at {@code introspect-timeout}, which then counts as such a
 * failure. The HTTP client's own {@code responseTimeout} only covers the wait after the request is
 * written; DNS, acquiring a pooled connection and the TCP connect come first, and any one of them
 * stalling would otherwise hold the caller's request for as long as it took.
 */
public class WebClientTokenIntrospector implements TokenIntrospector {

    private static final Logger log = LoggerFactory.getLogger(WebClientTokenIntrospector.class);

    private final WebClient webClient;
    private final String introspectUri;
    private final Duration introspectTimeout;

    public WebClientTokenIntrospector(WebClient webClient, GatewaySecurityProperties properties) {
        this.webClient = webClient;
        this.introspectUri = properties.getAuth().getIntrospectUri();
        this.introspectTimeout = properties.getAuth().getIntrospectTimeout();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Mono<IntrospectionResult> introspect(String token) {
        return webClient.get()
                .uri(introspectUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> toResult((Map<String, Object>) body))
                .timeout(introspectTimeout)
                .onErrorResume(err -> {
                    log.warn("Token introspection call failed: {}", err.getClass().getSimpleName());
                    return Mono.just(IntrospectionResult.inactive());
                })
                .defaultIfEmpty(IntrospectionResult.inactive());
    }

    private IntrospectionResult toResult(Map<String, Object> body) {
        if (body == null || !Boolean.TRUE.equals(body.get("active"))) {
            return IntrospectionResult.inactive();
        }
        String subject = body.get("sub") == null ? null : String.valueOf(body.get("sub"));
        List<String> roles = List.of();
        Object rawRoles = body.get("roles");
        if (rawRoles instanceof List<?> list) {
            roles = list.stream().map(String::valueOf).toList();
        }
        return new IntrospectionResult(true, subject, roles, expiry(body.get("exp")));
    }

    /**
     * Reads the {@code exp} claim (epoch seconds). The Auth Service reports {@code 0} for a token
     * without an expiry; that, a missing claim, or anything non-numeric yields {@code null}, which
     * {@link CachingTokenIntrospector} treats as "do not cache".
     */
    private static Instant expiry(Object rawExp) {
        if (rawExp instanceof Number number && number.longValue() > 0) {
            return Instant.ofEpochSecond(number.longValue());
        }
        return null;
    }
}
