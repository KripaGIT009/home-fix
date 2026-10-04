package com.homefix.gateway.filter;

import com.homefix.gateway.auth.IntrospectionResult;
import com.homefix.gateway.auth.TokenIntrospector;
import com.homefix.gateway.support.GatewayErrorWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validates the bearer token on every routed request by calling the Auth Service
 * {@code /auth/introspect} (Requirement 23.1). Requests without a valid, active token are rejected
 * with {@code 401 Unauthorized} <em>before</em> being routed to any microservice. The production
 * {@link TokenIntrospector} reuses an active result for a few seconds (see
 * {@code CachingTokenIntrospector}), so not every request costs an Auth Service call; an inactive
 * result or a failed call is never reused.
 *
 * <p>A small allow-list of unauthenticated edge endpoints (registration, login, token refresh,
 * health probes) bypasses introspection — these are the entry points that necessarily precede a
 * token existing. Everything else requires a valid token. A prefix matches whole path segments only
 * ({@code /auth/login} covers {@code /auth/login/password} but not {@code /auth/loginX}), and it is
 * matched against the decoded, dot-segment-resolved path, so an encoded or {@code ..}-laden spelling
 * cannot make a protected path look public. {@code /auth/logout} is deliberately not on the list:
 * the web and native clients reach the Auth Service directly for it, and through the gateway it
 * stays behind a valid token.
 *
 * <p>On success the {@link IntrospectionResult} is stored as an exchange attribute so the
 * downstream rate-limit filter can key on the subject and roles without a second introspection.
 */
@Component
public class JwtIntrospectionGatewayFilter implements GlobalFilter, Ordered {

    /** Runs after WAF but before rate limiting so limits key on the authenticated subject. */
    public static final int ORDER = -70;

    public static final String INTROSPECTION_ATTR = "homefix.introspection";

    /**
     * Path prefixes that are reachable without a JWT (auth entry points + health probes). Each is
     * matched as a whole path segment sequence; see {@link #isPublic(String)}. {@code /actuator} and
     * {@code /health} are listed for completeness: no route forwards them to a backend, and the
     * gateway's own actuator endpoints are served before routing, so this filter never sees them.
     */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/auth/register",
            "/auth/login",
            "/auth/token/refresh",
            "/auth/introspect",
            // Forgotten-password reset and the staff invitee's link (email-auth spec): both come
            // before the person has a token.
            "/auth/password",
            "/auth/invitations",
            // Razorpay's webhook carries no JWT; the Payment Service verifies its HMAC signature.
            "/payments/webhooks/razorpay",
            "/actuator",
            "/health");

    private final TokenIntrospector introspector;
    private final GatewayErrorWriter errorWriter;

    public JwtIntrospectionGatewayFilter(TokenIntrospector introspector, GatewayErrorWriter errorWriter) {
        this.introspector = introspector;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        String token = bearerToken(exchange);
        if (token == null) {
            return unauthorized(exchange);
        }

        return introspector.introspect(token)
                .flatMap(result -> {
                    if (!result.active()) {
                        return unauthorized(exchange);
                    }
                    exchange.getAttributes().put(INTROSPECTION_ATTR, result);
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED", "Authentication is required to access this resource.");
    }

    /**
     * Whether {@code rawPath} may be routed without a token.
     *
     * <p>The raw path is decoded, repeated slashes are collapsed and {@code .}/{@code ..} segments
     * are resolved first, so the decision is made on the path a backend will actually serve. A
     * prefix then matches only on a segment boundary: the path equals it or continues with
     * {@code /}. A bare {@code startsWith} would make {@code /auth/loginX} or {@code /healthz}
     * public. A path that cannot be decoded is never public.
     */
    static boolean isPublic(String rawPath) {
        String path = normalise(rawPath);
        if (path == null) {
            return false;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Decoded, slash-collapsed, dot-segment-resolved form of {@code rawPath}; null if malformed. */
    private static String normalise(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String decoded;
        try {
            decoded = UriUtils.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            return null;
        }
        return StringUtils.cleanPath(decoded.replaceAll("/{2,}", "/"));
    }

    private static String bearerToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(header) || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
