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

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.domain.ProviderSearchUnavailableException;
import com.homefix.dispatch.domain.SearchingFailedOutcome;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the outbound HTTP adapters against a loopback stub server: the booking-transition adapter
 * (Requirements 8.6, 8.9), and the provider query's internal credential, response mapping and
 * failure reporting (Requirement 8.2). Mirrors the pattern of
 * {@link ProviderQueryResilienceIntegrationTest}.
 */
class DispatchHttpAdaptersTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{}";
    private volatile String lastInternalKey;
    private volatile String lastQuery;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastInternalKey = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
            lastQuery = exchange.getRequestURI().getRawQuery();
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
        p.setBookingServiceBaseUrl(base);
        p.setProviderServiceBaseUrl(base);
        return p;
    }

    // ---- Booking-transition adapter (Requirements 8.6, 8.9) ------------------------------------

    @Test
    void bookingTransitionAdapter_marksProviderAcceptedAndSearchingFailed() {
        statusToReturn = 200;
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");

        adapter.markProviderAccepted(UUID.randomUUID(), UUID.randomUUID());
        adapter.markSearchingFailed(UUID.randomUUID());

        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void bookingTransitionAdapter_searchingFailedAnsweredWithAwaitingAssignment_reportsTheTenantRouting() {
        // Requirement MT-4.2: booking-service answers the call with the booking (BookingResponse);
        // AWAITING_ASSIGNMENT means it was handed to partner agencies rather than failed.
        bodyToReturn = """
                {"bookingId":"%s","reference":"HF-1","status":"AWAITING_ASSIGNMENT","emergency":true,
                 "scheduledAt":null,"estimatedTotal":499.00,"cancellationFee":null,"estimate":null}"""
                .formatted(UUID.randomUUID());
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThat(adapter.markSearchingFailed(UUID.randomUUID()))
                .isEqualTo(SearchingFailedOutcome.AWAITING_ASSIGNMENT);
        assertThat(lastInternalKey).isEqualTo("test-internal-key");
    }

    @Test
    void bookingTransitionAdapter_searchingFailedAnsweredWithSearchingFailed_reportsTheFailure() {
        bodyToReturn = """
                {"bookingId":"%s","reference":"HF-2","status":"SEARCHING_FAILED","emergency":false}"""
                .formatted(UUID.randomUUID());
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThat(adapter.markSearchingFailed(UUID.randomUUID()))
                .isEqualTo(SearchingFailedOutcome.SEARCHING_FAILED);
    }

    @Test
    void bookingTransitionAdapter_searchingFailedWithoutABody_isTreatedAsFailed() {
        // An empty 200 (a Booking Service without Tenants) keeps today's behaviour: the notices go out.
        bodyToReturn = "";
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThat(adapter.markSearchingFailed(UUID.randomUUID()))
                .isEqualTo(SearchingFailedOutcome.SEARCHING_FAILED);
    }

    @Test
    void bookingTransitionAdapter_searchingFailedForUnknownBooking_meansNotSearchable() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"BOOKING_NOT_FOUND\"}";
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThatThrownBy(() -> adapter.markSearchingFailed(UUID.randomUUID()))
                .isInstanceOf(BookingNotSearchableException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void bookingTransitionAdapter_serverError_raisesRecoverableTransitionException() {
        statusToReturn = 503;
        bodyToReturn = "{\"error\":\"down\"}";
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThatThrownBy(() -> adapter.markSearchingFailed(UUID.randomUUID()))
                .isInstanceOf(HttpBookingTransitionAdapter.BookingTransitionException.class);
    }

    @Test
    void bookingTransitionAdapter_conflict_meansTheBookingIsNoLongerSearchable() {
        statusToReturn = 409;
        bodyToReturn = "{\"errorCode\":\"INVALID_TRANSITION\"}";
        HttpBookingTransitionAdapter adapter =
                new HttpBookingTransitionAdapter(props(), new ResilienceFactory(), "test-internal-key");
        UUID booking = UUID.randomUUID();

        assertThatThrownBy(() -> adapter.markProviderAccepted(booking, UUID.randomUUID()))
                .isInstanceOf(BookingNotSearchableException.class)
                .satisfies(e -> assertThat(((BookingNotSearchableException) e).bookingId()).isEqualTo(booking));
        // A 409 is a definitive answer, not a transient failure: it is not retried.
        assertThat(requests.get()).isEqualTo(1);
    }

    // ---- Provider query adapter (Requirement 8.2) ----------------------------------------------

    private static DispatchRequest dispatchRequest() {
        return new DispatchRequest(UUID.randomUUID(), UUID.randomUUID(), 12.9, 77.6,
                UUID.randomUUID(), List.of("plumbing"), true, Instant.now());
    }

    @Test
    void providerQueryAdapter_presentsTheInternalKeyAndMapsTheCandidates() {
        UUID provider = UUID.randomUUID();
        bodyToReturn = """
                {"providers":[{"providerId":"%s","distanceScore":0.9,"availabilityScore":1.0,
                 "ratingScore":0.8,"skillScore":0.5,"performanceScore":0.7}]}""".formatted(provider);
        HttpProviderQueryAdapter adapter =
                new HttpProviderQueryAdapter(props(), new ResilienceFactory(), "test-internal-key");

        List<ProviderCandidate> candidates = adapter.findEligibleProviders(dispatchRequest(), 10.0);

        assertThat(lastInternalKey).isEqualTo("test-internal-key");
        assertThat(lastQuery).contains("radiusKm=10.0").contains("emergency=true")
                .contains("skillTags=plumbing");
        assertThat(candidates).singleElement().satisfies(c -> {
            assertThat(c.providerId()).isEqualTo(provider);
            assertThat(c.components().distanceScore()).isEqualTo(0.9);
            assertThat(c.components().performanceScore()).isEqualTo(0.7);
        });
    }

    @Test
    void providerQueryAdapter_refusedCredential_isReportedAsUnavailableNotAsAnEmptyMarket() {
        // A mismatched INTERNAL_API_KEY used to read as "no providers", failing every booking.
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Invalid internal credentials\"}";
        HttpProviderQueryAdapter adapter =
                new HttpProviderQueryAdapter(props(), new ResilienceFactory(), "wrong-key");

        assertThatThrownBy(() -> adapter.findEligibleProviders(dispatchRequest(), 10.0))
                .isInstanceOf(ProviderSearchUnavailableException.class)
                .hasMessageContaining("401");
        // Retrying the same key cannot help.
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void providerQueryAdapter_forbidden_isReportedAsUnavailable() {
        statusToReturn = 403;
        bodyToReturn = "{\"errorCode\":\"FORBIDDEN\"}";
        HttpProviderQueryAdapter adapter =
                new HttpProviderQueryAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThatThrownBy(() -> adapter.findEligibleProviders(dispatchRequest(), 10.0))
                .isInstanceOf(ProviderSearchUnavailableException.class);
    }

    @Test
    void providerQueryAdapter_serverError_isRetriedThenReportedAsUnavailable() {
        statusToReturn = 503;
        bodyToReturn = "{\"error\":\"down\"}";
        HttpProviderQueryAdapter adapter =
                new HttpProviderQueryAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThatThrownBy(() -> adapter.findEligibleProviders(dispatchRequest(), 10.0))
                .isInstanceOf(ProviderSearchUnavailableException.class);
        // A 5xx is transient: the shared stack retried it before giving up.
        assertThat(requests.get()).isGreaterThan(1);
    }

    @Test
    void providerQueryAdapter_emptyAnswer_isAGenuinelyEmptyMarket() {
        bodyToReturn = "{\"providers\":[]}";
        HttpProviderQueryAdapter adapter =
                new HttpProviderQueryAdapter(props(), new ResilienceFactory(), "test-internal-key");

        assertThat(adapter.findEligibleProviders(dispatchRequest(), 10.0)).isEmpty();
    }

    @Test
    void providerQueryAdapter_refusesToStartWithoutAnInternalKey() {
        assertThatThrownBy(() -> new HttpProviderQueryAdapter(props(), new ResilienceFactory(), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key");
    }
}
