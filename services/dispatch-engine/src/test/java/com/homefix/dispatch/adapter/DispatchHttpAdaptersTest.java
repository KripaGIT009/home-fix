package com.homefix.dispatch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.port.JobOfferPort;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the outbound HTTP adapters against a loopback stub server: the best-effort notification
 * adapter (Requirement 8.9), the job-offer adapter's response mapping (Requirements 8.5-8.7), and
 * the booking-transition adapter (Requirements 8.6, 8.9). Mirrors the pattern of
 * {@link ProviderQueryResilienceIntegrationTest}.
 */
class DispatchHttpAdaptersTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToReturn, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DispatchClientProperties props() {
        DispatchClientProperties p = new DispatchClientProperties();
        String base = "http://127.0.0.1:" + port;
        p.setNotificationServiceBaseUrl(base);
        p.setBookingServiceBaseUrl(base);
        p.setProviderServiceBaseUrl(base);
        return p;
    }

    // ---- Notification adapter (best-effort, Requirement 8.9) -----------------------------------

    @Test
    void notificationAdapter_deliversNoProviderAndDispatcherAlert() {
        statusToReturn = 200;
        HttpNotificationAdapter adapter = new HttpNotificationAdapter(props(), new ResilienceFactory());

        adapter.notifyCustomerNoProviderAvailable(UUID.randomUUID(), UUID.randomUUID());
        adapter.alertDispatcherTeam(UUID.randomUUID());

        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void notificationAdapter_serverError_degradesToNoOpWithoutThrowing() {
        statusToReturn = 503;
        bodyToReturn = "{\"error\":\"down\"}";
        HttpNotificationAdapter adapter = new HttpNotificationAdapter(props(), new ResilienceFactory());

        // Best-effort: must not throw even when the Notification Service is failing.
        adapter.notifyCustomerNoProviderAvailable(UUID.randomUUID(), UUID.randomUUID());
    }

    // ---- Job-offer adapter (Requirements 8.5-8.7) ----------------------------------------------

    @Test
    void jobOfferAdapter_mapsAcceptedRejectedAndTimeout() {
        HttpJobOfferAdapter adapter = new HttpJobOfferAdapter(props());
        UUID booking = UUID.randomUUID();
        UUID provider = UUID.randomUUID();

        bodyToReturn = "{\"outcome\":\"ACCEPTED\"}";
        assertThat(adapter.offer(booking, provider, Duration.ofSeconds(60)))
                .isEqualTo(JobOfferPort.OfferOutcome.ACCEPTED);

        bodyToReturn = "{\"outcome\":\"REJECTED\"}";
        assertThat(adapter.offer(booking, provider, Duration.ofSeconds(60)))
                .isEqualTo(JobOfferPort.OfferOutcome.REJECTED);

        bodyToReturn = "{\"outcome\":\"SOMETHING_ELSE\"}";
        assertThat(adapter.offer(booking, provider, Duration.ofSeconds(60)))
                .isEqualTo(JobOfferPort.OfferOutcome.TIMED_OUT);
    }

    @Test
    void jobOfferAdapter_transportError_returnsTimeout() {
        HttpJobOfferAdapter adapter = new HttpJobOfferAdapter(props());
        server.stop(0); // no server -> connection failure treated as timeout

        assertThat(adapter.offer(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(1)))
                .isEqualTo(JobOfferPort.OfferOutcome.TIMED_OUT);
    }

    // ---- Booking-transition adapter (Requirements 8.6, 8.9) ------------------------------------

    @Test
    void bookingTransitionAdapter_marksProviderAcceptedAndSearchingFailed() {
        statusToReturn = 200;
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory());

        adapter.markProviderAccepted(UUID.randomUUID(), UUID.randomUUID());
        adapter.markSearchingFailed(UUID.randomUUID());

        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void bookingTransitionAdapter_serverError_raisesRecoverableTransitionException() {
        statusToReturn = 503;
        bodyToReturn = "{\"error\":\"down\"}";
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory());

        assertThatThrownBy(() -> adapter.markSearchingFailed(UUID.randomUUID()))
                .isInstanceOf(HttpBookingTransitionAdapter.BookingTransitionException.class);
    }
}
