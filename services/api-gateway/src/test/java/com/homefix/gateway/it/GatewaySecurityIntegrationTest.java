package com.homefix.gateway.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.homefix.gateway.auth.IntrospectionResult;
import com.homefix.gateway.auth.TokenIntrospector;
import com.homefix.gateway.ratelimit.RateLimitCounter;
import com.homefix.gateway.ratelimit.RateLimitPolicy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Integration tests for the API Gateway security controls (Task 28, Requirement 23).
 *
 * <p>Boots the full {@link com.homefix.gateway.ApiGatewayApplication} on a random port and drives it
 * through {@link WebTestClient} so the real global-filter chain (HTTPS redirect &rarr; correlation id
 * &rarr; WAF &rarr; JWT introspection &rarr; OTP throttle &rarr; per-user rate limit) executes exactly as it
 * would in production. The only substitutions are the collaborators that would otherwise require
 * live infrastructure:
 * <ul>
 *   <li>a stub {@link TokenIntrospector} that treats the token {@code "valid-customer"} as an active
 *       CUSTOMER and everything else as inactive — so no live Auth Service is needed;</li>
 *   <li>a deterministic in-memory {@link RateLimitCounter} — so no live Redis is needed.</li>
 * </ul>
 * Both are declared {@code @ConditionalOnMissingBean} in production wiring, so these test beans win.
 *
 * <p>Downstream routing targets an in-process {@code forward:} controller, so a permitted request
 * receives a real 200 rather than a connection error.
 *
 * <p>Covers the three integration scenarios required by Task 28:
 * <ol>
 *   <li>unauthenticated request &rarr; 401 (Requirement 23.1);</li>
 *   <li>CUSTOMER at request 101 within one minute &rarr; 429 + {@code Retry-After} (Requirement 23.3, 23.5);</li>
 *   <li>plain HTTP request &rarr; 301 redirect to HTTPS (Requirement 23.7).</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Route a test path to an in-process handler so permitted requests get a real 200.
                "spring.cloud.gateway.routes[0].id=test-downstream",
                "spring.cloud.gateway.routes[0].uri=forward:/__downstream",
                "spring.cloud.gateway.routes[0].predicates[0]=Path=/bookings/**",
                // Keep OTP path at its default so it does not interfere with the /bookings tests.
                "homefix.gateway.https-enforced=true"
        })
class GatewaySecurityIntegrationTest {

    private static final String VALID_CUSTOMER_TOKEN = "valid-customer";

    @org.springframework.beans.factory.annotation.Autowired
    private WebTestClient webTestClient;

    // ── Scenario 1: unauthenticated request → 401 (Requirement 23.1) ──────────────────────────
    @Test
    void unauthenticatedRequestIsRejectedWith401() {
        webTestClient.get()
                .uri("/bookings/123")
                .header("X-Forwarded-Proto", "https") // pass the HTTPS gate so we test auth, not redirect
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("UNAUTHORIZED")
                // The rejection is correlated but reveals no internal detail (Requirement 23.9, 23.6).
                .jsonPath("$.correlationId").exists();
    }

    @Test
    void invalidTokenIsRejectedWith401() {
        webTestClient.get()
                .uri("/bookings/123")
                .header("X-Forwarded-Proto", "https")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Scenario 2: CUSTOMER at 101 req/min → 429 + Retry-After (Requirement 23.3, 23.5) ──────
    @Test
    void customerExceedingRateLimitReceives429WithRetryAfter() {
        // The first 100 requests within the fixed one-minute window are permitted.
        for (int i = 1; i <= RateLimitPolicy.defaults().getCustomerLimit(); i++) {
            webTestClient.get()
                    .uri("/bookings/list")
                    .header("X-Forwarded-Proto", "https")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_CUSTOMER_TOKEN)
                    .exchange()
                    .expectStatus().isOk();
        }

        // The 101st request in the same window is rejected with 429 + Retry-After.
        webTestClient.get()
                .uri("/bookings/list")
                .header("X-Forwarded-Proto", "https")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_CUSTOMER_TOKEN)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER,
                        String.valueOf(RateLimitPolicy.WINDOW_SECONDS))
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    // ── Scenario 2b: WAF blocks a SQL-injection payload with a generic 400 (Requirement 23.6) ─
    @Test
    void sqlInjectionPayloadIsBlockedWithGeneric400() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bookings/search")
                        .queryParam("q", "' OR 1=1 --")
                        .build())
                .header("X-Forwarded-Proto", "https")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_CUSTOMER_TOKEN)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                // Generic error body — no internal detail about which rule fired (Requirement 23.6).
                .jsonPath("$.errorCode").isEqualTo("BAD_REQUEST")
                .jsonPath("$.correlationId").exists();
    }

    // ── Scenario 3: plain HTTP request → 301 redirect to HTTPS (Requirement 23.7) ─────────────
    @Test
    void plainHttpRequestIsRedirectedToHttpsWith301() {
        // No X-Forwarded-Proto and a plain-http local request → the gateway treats it as insecure.
        webTestClient.get()
                .uri("/bookings/123")
                .header("X-Forwarded-Proto", "http")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.MOVED_PERMANENTLY)
                .expectHeader().value(HttpHeaders.LOCATION, location ->
                        assertThat(location).startsWith("https://"));
    }

    // ── Test wiring: stub introspector + in-memory counter + in-process downstream ────────────
    @TestConfiguration
    static class TestBeans {

        /** Treats a single fixed token as an active CUSTOMER; everything else is inactive. */
        @Bean
        @Primary
        TokenIntrospector stubIntrospector() {
            return token -> VALID_CUSTOMER_TOKEN.equals(token)
                    ? Mono.just(new IntrospectionResult(true, "customer-1", List.of("CUSTOMER")))
                    : Mono.just(IntrospectionResult.inactive());
        }

        /** Deterministic fixed-window counter — no Redis. TTL is irrelevant within a single test. */
        @Bean
        @Primary
        RateLimitCounter inMemoryRateLimitCounter() {
            ConcurrentHashMap<String, AtomicLong> counts = new ConcurrentHashMap<>();
            return (key, window) ->
                    Mono.just(counts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet());
        }

        /** In-process forward target so permitted requests resolve to a real 200. */
        @Bean
        DownstreamStubController downstreamStubController() {
            return new DownstreamStubController();
        }
    }

    @RestController
    static class DownstreamStubController {
        @GetMapping(value = "/__downstream", produces = MediaType.APPLICATION_JSON_VALUE)
        Mono<String> ok() {
            return Mono.just("{\"status\":\"ok\"}");
        }
    }
}
