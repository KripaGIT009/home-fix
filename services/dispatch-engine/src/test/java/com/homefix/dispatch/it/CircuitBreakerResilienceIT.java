package com.homefix.dispatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homefix.shared.resilience.FallbackDecision;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.homefix.shared.resilience.TransientFailures;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Integration test for the circuit-breaker scenario of Task 45 (Requirement 24.1, 24.4).
 *
 * <p>Drives the shared resilience primitive ({@link ResilientCall} over a real
 * {@link ResilienceFactory}) against a downstream dependency exposed as a loopback HTTP server
 * whose failure rate we control per request. This is the same primitive every HomeFix service
 * uses for synchronous cross-service calls, so exercising it here validates the platform-wide
 * behaviour once.
 *
 * <p>Injecting a 50% failure rate across a full 10-call window (Requirement 24.1) we assert:
 * <ul>
 *   <li>the breaker transitions to OPEN once the window reaches the 50% failure threshold;</li>
 *   <li>with the breaker OPEN the caller receives the degraded fallback response and the
 *       dependency is no longer contacted (fail-fast); and</li>
 *   <li>a WARN-level log naming the failed downstream dependency is emitted (Requirement 24.4).</li>
 * </ul>
 */
class CircuitBreakerResilienceIT {

    /** Name the breaker/metrics/WARN log attribute the failing dependency by (Requirement 24.4). */
    private static final String DEPENDENCY = "pricing-engine";

    private HttpServer server;
    private int port;
    private final AtomicInteger requestsServed = new AtomicInteger();
    private volatile boolean failMode = false;

    private ListAppender<ILoggingEvent> appender;
    private Logger callLogger;

    @BeforeEach
    void startServerAndCaptureLogs() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/price", exchange -> {
            requestsServed.incrementAndGet();
            int status;
            byte[] body;
            if (failMode) {
                // Inject an HTTP 5xx transient failure on the downstream (Requirement 24.2).
                status = 503;
                body = "{\"error\":\"pricing unavailable\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "{\"total\":\"42.00\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();

        // Capture WARN logs emitted by the shared ResilientCall on breaker-open (Requirement 24.4).
        callLogger = (Logger) LoggerFactory.getLogger(ResilientCall.class);
        appender = new ListAppender<>();
        appender.start();
        callLogger.addAppender(appender);
    }

    @AfterEach
    void stopServerAndDetachLogs() {
        if (callLogger != null && appender != null) {
            callLogger.detachAppender(appender);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("50% failure rate over the 10-call window opens the breaker, then fallback is returned and a WARN is logged")
    void fiftyPercentFailuresOpenBreakerThenFallbackAndWarn() {
        ResilienceFactory factory = new ResilienceFactory();
        // Degrade to a sentinel "service degraded" response when the dependency is unavailable.
        FallbackDecision<String> fallback = cause -> "DEGRADED";
        ResilientCall<String> pricingCall = ResilientCall.forDependency(
                factory, DEPENDENCY, Duration.ofSeconds(5), fallback);
        CircuitBreaker breaker = factory.circuitBreaker(DEPENDENCY);

        HttpClient http = HttpClient.newHttpClient();

        // Alternate success / failure across a full 10-call window -> exactly 50% failure rate.
        for (int i = 0; i < 10; i++) {
            failMode = (i % 2 == 1);
            String result = pricingCall.execute(() -> callPricing(http));
            // Every call returns a usable value: the real body on success, or the degraded
            // fallback on a failed attempt — the caller never sees an exception (Requirement 24.4).
            assertThat(result).isNotNull();
        }

        // Requirement 24.1: the breaker is OPEN once the 10-call window shows a 50% failure rate.
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Requirement 24.4: while OPEN the dependency is not contacted and the fallback is returned.
        int servedBeforeOpenCall = requestsServed.get();
        String degraded = pricingCall.execute(() -> callPricing(http));
        assertThat(degraded).isEqualTo("DEGRADED");
        assertThat(requestsServed.get())
                .as("an open breaker must short-circuit and not hit the dependency")
                .isEqualTo(servedBeforeOpenCall);

        // Requirement 24.4: a WARN log naming the failed downstream dependency was emitted.
        assertThat(appender.list).anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Circuit breaker OPEN")
                        && e.getFormattedMessage().contains(DEPENDENCY));
    }

    /**
     * Issues one GET to the stub dependency. A 5xx is mapped to a transient
     * {@link TransientFailures.ServerErrorException} so the shared retry/breaker act on it exactly
     * as the production {@code RestClient} adapters do (Requirement 24.2).
     */
    private String callPricing(HttpClient http) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/price"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) {
                throw new TransientFailures.ServerErrorException(
                        response.statusCode(), "Pricing Engine returned 5xx");
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }
}
