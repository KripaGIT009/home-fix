package com.homefix.dispatch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.domain.ProviderSearchUnavailableException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.shared.resilience.ResilientCall;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Integration test for Requirement 24: injects downstream failures at the Provider Service HTTP
 * boundary and verifies the circuit breaker opens after 50% failures over the 10-call window, the
 * caller then fails fast with {@link ProviderSearchUnavailableException} (never an empty "no
 * providers" list), and a WARN log naming the failed dependency is emitted (Requirements 24.1,
 * 24.4).
 *
 * <p>The test drives the real {@link HttpProviderQueryAdapter} — including its real
 * {@link ResilienceFactory}-backed circuit breaker — against a stub server bound to a loopback port
 * whose failure rate we control per request.
 */
class ProviderQueryResilienceIntegrationTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requestsServed = new AtomicInteger();
    private volatile boolean failMode = false;

    private ListAppender<ILoggingEvent> appender;
    private Logger callLogger;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/providers/eligible", exchange -> {
            requestsServed.incrementAndGet();
            byte[] body;
            int status;
            if (failMode) {
                // Inject an HTTP 5xx transient failure (Requirement 24.2).
                status = 503;
                body = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "{\"providers\":[]}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();

        callLogger = (Logger) LoggerFactory.getLogger(ResilientCall.class);
        appender = new ListAppender<>();
        appender.start();
        callLogger.addAppender(appender);
    }

    @AfterEach
    void stopServer() {
        callLogger.detachAppender(appender);
        server.stop(0);
    }

    @Test
    void circuitOpensAfterFiftyPercentFailuresThenReturnsFallbackAndWarns() {
        DispatchClientProperties props = new DispatchClientProperties();
        props.setProviderServiceBaseUrl("http://127.0.0.1:" + port);
        ResilienceFactory factory = new ResilienceFactory();
        HttpProviderQueryAdapter adapter = new HttpProviderQueryAdapter(props, factory, "test-internal-key");

        DispatchRequest request = new DispatchRequest(
                UUID.randomUUID(), UUID.randomUUID(), 12.9, 77.6,
                UUID.randomUUID(), List.of("plumbing"), true, Instant.now());

        CircuitBreaker breaker = factory.circuitBreaker(HttpProviderQueryAdapter.DEPENDENCY);

        // Alternate success / failure across a full 10-call window → exactly 50% failure rate.
        for (int i = 0; i < 10; i++) {
            failMode = (i % 2 == 1);
            if (failMode) {
                // A failed call is reported as unavailable, not as an empty market.
                assertThatThrownBy(() -> adapter.findEligibleProviders(request, 10.0))
                        .isInstanceOf(ProviderSearchUnavailableException.class);
            } else {
                List<ProviderCandidate> result = adapter.findEligibleProviders(request, 10.0);
                assertThat(result).isEmpty();
            }
        }

        // Requirement 24.1: breaker is OPEN once the window shows a 50% failure rate.
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // While OPEN the dependency is not called at all; the caller fails fast.
        int servedBeforeOpenCall = requestsServed.get();
        assertThatThrownBy(() -> adapter.findEligibleProviders(request, 10.0))
                .isInstanceOf(ProviderSearchUnavailableException.class);
        assertThat(requestsServed.get())
                .as("open breaker must short-circuit and not hit the dependency")
                .isEqualTo(servedBeforeOpenCall);

        // Requirement 24.4: a WARN log naming the provider-service dependency was emitted.
        assertThat(appender.list).anyMatch(e ->
                e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Circuit breaker OPEN")
                        && e.getFormattedMessage().contains(HttpProviderQueryAdapter.DEPENDENCY));
    }
}
