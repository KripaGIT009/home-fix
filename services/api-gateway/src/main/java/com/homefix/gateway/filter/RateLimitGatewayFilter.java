package com.homefix.gateway.filter;

import com.homefix.gateway.auth.IntrospectionResult;
import com.homefix.gateway.ratelimit.RateLimitCounter;
import com.homefix.gateway.ratelimit.RateLimitPolicy;
import com.homefix.gateway.support.GatewayErrorWriter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Enforces per-user request rate limiting (Requirement 23.3, 23.5, Property 27).
 *
 * <p>For each authenticated request the filter increments a fixed-window counter keyed on the
 * user's subject and compares the running count to the limit for the caller's role
 * ({@link RateLimitPolicy}: CUSTOMER 100/min, PROVIDER 60/min). Requests up to the limit are
 * permitted; the first request that exceeds it — and every subsequent request in the same window —
 * is rejected with {@code 429 Too Many Requests} and a {@code Retry-After} header (in seconds until
 * the window resets).
 *
 * <p>Runs after JWT introspection so the {@link IntrospectionResult} (subject + roles) is available
 * from the exchange attribute. Requests without an introspection result (public endpoints) are not
 * subject to per-user limits here.
 */
@Component
public class RateLimitGatewayFilter implements GlobalFilter, Ordered {

    /** Runs last among the security filters — after auth has established identity. */
    public static final int ORDER = -50;

    private final RateLimitPolicy policy;
    private final RateLimitCounter counter;
    private final GatewayErrorWriter errorWriter;

    public RateLimitGatewayFilter(RateLimitPolicy policy,
                                  RateLimitCounter counter,
                                  GatewayErrorWriter errorWriter) {
        this.policy = policy;
        this.counter = counter;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Object attr = exchange.getAttribute(JwtIntrospectionGatewayFilter.INTROSPECTION_ATTR);
        if (!(attr instanceof IntrospectionResult result) || !result.active()) {
            // Unauthenticated / public request — per-user limiting does not apply here.
            return chain.filter(exchange);
        }

        int limit = policy.limitFor(result.roles());
        String key = policy.rateLimitKey(result.subject());
        Duration window = Duration.ofSeconds(RateLimitPolicy.WINDOW_SECONDS);

        return counter.incrementAndGet(key, window)
                .flatMap(count -> {
                    if (count > limit) {
                        return errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                                "RATE_LIMIT_EXCEEDED",
                                "Rate limit exceeded. Retry after the indicated interval.",
                                (long) RateLimitPolicy.WINDOW_SECONDS);
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
