package com.homefix.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.gateway.auth.IntrospectionResult;
import com.homefix.gateway.ratelimit.RateLimitCounter;
import com.homefix.gateway.ratelimit.RateLimitPolicy;
import com.homefix.gateway.support.GatewayErrorWriter;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Property-based test for the API Gateway correctness property 27 (design.md "Correctness
 * Properties", Requirements 23.3 / 23.5). Runs a minimum of 100 tries and is tagged with the
 * required {@code Feature: homefix-platform, Property N} label.
 *
 * <p>Property 27 lives in the gateway's {@link RateLimitGatewayFilter}, so — per the task guidance
 * for gateway-filter logic — it is tested at that unit level: the real filter runs with the real
 * {@link RateLimitPolicy#defaults()} (CUSTOMER = 100/min) over a deterministic in-memory
 * {@link RateLimitCounter} (no Redis). For a CUSTOMER user, requests up to the limit are forwarded
 * down the chain; every request beyond the limit within the same window is rejected with
 * {@code 429 Too Many Requests} carrying a {@code Retry-After} header.
 */
class RateLimitGatewayFilterPropertiesTest {

    private static final int CUSTOMER_LIMIT = 100;

    @Property(tries = 100)
    @Label("Feature: homefix-platform, Property 27: Rate limiting correctness")
    void customerRequestsUpToLimitSucceedThenReceive429WithRetryAfter(
            @ForAll @IntRange(min = 0, max = 40) int extraRequests) {

        RateLimitPolicy policy = RateLimitPolicy.defaults();
        InMemoryRateLimitCounter counter = new InMemoryRateLimitCounter();
        GatewayErrorWriter errorWriter = new GatewayErrorWriter(new ObjectMapper());
        RateLimitGatewayFilter filter = new RateLimitGatewayFilter(policy, counter, errorWriter);

        String subject = "user-" + extraRequests; // distinct key per try keeps windows isolated
        int totalRequests = CUSTOMER_LIMIT + extraRequests;

        AtomicInteger forwarded = new AtomicInteger();
        GatewayFilterChain chain = ex -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };

        int rejected = 0;
        for (int i = 1; i <= totalRequests; i++) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/bookings").build());
            exchange.getAttributes().put(JwtIntrospectionGatewayFilter.INTROSPECTION_ATTR,
                    new IntrospectionResult(true, subject, List.of("CUSTOMER")));

            filter.filter(exchange, chain).block();

            if (i <= CUSTOMER_LIMIT) {
                // Up to the limit: request is forwarded, no rejection status set.
                assertThat(exchange.getResponse().getStatusCode())
                        .as("request %d of %d should be permitted", i, totalRequests)
                        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            } else {
                // Beyond the limit within the same window: 429 + Retry-After (Property 27).
                rejected++;
                assertThat(exchange.getResponse().getStatusCode())
                        .as("request %d of %d should be rate limited", i, totalRequests)
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                String retryAfter = exchange.getResponse().getHeaders()
                        .getFirst(HttpHeaders.RETRY_AFTER);
                assertThat(retryAfter)
                        .as("429 response must carry a Retry-After header")
                        .isEqualTo(String.valueOf(RateLimitPolicy.WINDOW_SECONDS));
            }
        }

        // Exactly the first `limit` requests were forwarded; every excess request was rejected.
        assertThat(forwarded.get()).isEqualTo(CUSTOMER_LIMIT);
        assertThat(rejected).isEqualTo(extraRequests);
    }

    /**
     * Deterministic in-memory {@link RateLimitCounter}: a fixed-window counter per key with no
     * Redis and no expiry (a single window is all a single try exercises).
     */
    private static final class InMemoryRateLimitCounter implements RateLimitCounter {
        private final ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();

        @Override
        public Mono<Long> incrementAndGet(String key, Duration window) {
            return Mono.just(counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet());
        }
    }
}
