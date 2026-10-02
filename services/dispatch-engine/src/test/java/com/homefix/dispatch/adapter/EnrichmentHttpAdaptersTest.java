package com.homefix.dispatch.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.homefix.dispatch.config.DispatchClientProperties;
import com.homefix.dispatch.domain.EnrichmentUnavailableException;
import com.homefix.dispatch.domain.UnresolvableBookingException;
import com.homefix.dispatch.port.CustomerAddressPort.ResolvedAddress;
import com.homefix.dispatch.service.BookingEnrichmentService;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests the two enrichment adapters (Requirement 8.2) against a loopback stub server, the same
 * pattern as {@link DispatchHttpAdaptersTest}: the Customer Service address lookup, including the
 * service credential it presents and how each response class is reported (resolved, permanently
 * missing, or unavailable), and the Service Catalog skill-tag lookup.
 */
class EnrichmentHttpAdaptersTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{}";
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            String key = exchange.getRequestHeaders().getFirst(HttpCustomerAddressAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToReturn, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpCustomerAddressAdapter addressAdapter() {
        return new HttpCustomerAddressAdapter(baseUrl, new ResilienceFactory(), KEY, Duration.ofSeconds(2));
    }

    // ---- Customer Service address lookup -------------------------------------------------------

    @Test
    void address_resolvesOwnerAndCoordinatesPresentingTheServiceCredential() {
        UUID addressId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        bodyToReturn = "{\"addressId\":\"" + addressId + "\",\"customerId\":\"" + customerId
                + "\",\"lat\":25.5941,\"lng\":85.1376}";

        ResolvedAddress address = addressAdapter().lookup(addressId);

        assertThat(address).isEqualTo(new ResolvedAddress(addressId, customerId, 25.5941, 85.1376));
        assertThat(paths).containsExactly("/internal/addresses/" + addressId);
        assertThat(presentedKeys).containsExactly(KEY);
    }

    @Test
    void address_404WithAddressNotFound_isPermanentAndNotRetried() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"ADDRESS_NOT_FOUND\",\"message\":\"Address not found\"}";
        UUID addressId = UUID.randomUUID();

        assertThatThrownBy(() -> addressAdapter().lookup(addressId))
                .isInstanceOf(UnresolvableBookingException.class)
                .hasMessageContaining(addressId.toString())
                .hasMessageContaining("ADDRESS_NOT_FOUND");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void address_bare404WithoutTheErrorCode_isUnavailableNotPermanent() {
        // A wrong base URL, or a Customer Service build without the endpoint, must not
        // permanently refuse every booking.
        statusToReturn = 404;
        bodyToReturn = "{\"timestamp\":\"2026-10-02T00:00:00Z\",\"status\":404,\"error\":\"Not Found\"}";

        assertThatThrownBy(() -> addressAdapter().lookup(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class)
                .hasMessageContaining("404");
    }

    @Test
    void address_serverError_isRetriedThenReportedUnavailable() {
        statusToReturn = 503;
        bodyToReturn = "{\"error\":\"down\"}";

        assertThatThrownBy(() -> addressAdapter().lookup(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class);
        // The shared retry re-issues transient failures: 3 attempts in total (Requirement 24.2).
        assertThat(requests.get()).isEqualTo(ResilienceFactory.MAX_ATTEMPTS);
    }

    @Test
    void address_slowResponse_timesOutAndIsReportedUnavailable() {
        delayMillis = 1_500;
        HttpCustomerAddressAdapter adapter = new HttpCustomerAddressAdapter(
                baseUrl, new ResilienceFactory(), KEY, Duration.ofMillis(300));

        assertThatThrownBy(() -> adapter.lookup(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class);
    }

    @Test
    void address_refusedCredential_isReportedUnavailable() {
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Invalid internal credentials\"}";

        assertThatThrownBy(() -> addressAdapter().lookup(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class)
                .hasMessageContaining("401");
        // A 4xx is not transient, so the resilience stack does not hammer the dependency.
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void address_unreachableService_isReportedUnavailable() {
        HttpCustomerAddressAdapter adapter = addressAdapter();
        server.stop(0);

        assertThatThrownBy(() -> adapter.lookup(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class);
    }

    @Test
    void address_missingCredential_failsAtStartup() {
        DispatchClientProperties props = new DispatchClientProperties();
        props.setCustomerServiceBaseUrl(baseUrl);

        assertThatThrownBy(() -> new HttpCustomerAddressAdapter(props, new ResilienceFactory(), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key");
    }

    // ---- Service Catalog skill tags ------------------------------------------------------------

    @Test
    void catalog_returnsTheSubcategorysSkillTagsFromThePublicListing() {
        UUID wanted = UUID.randomUUID();
        bodyToReturn = "[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Cleaning\",\"subcategories\":["
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"skillTags\":[\"cleaning\"]}]},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Plumbing\",\"subcategories\":["
                + "{\"id\":\"" + wanted + "\",\"name\":\"Tap / Faucet Repair\","
                + "\"skillTags\":[\"plumbing\",\"tap-repair\"]}]}]";
        DispatchClientProperties props = new DispatchClientProperties();
        props.setCatalogServiceBaseUrl(baseUrl);

        List<String> tags = new HttpCatalogSkillsAdapter(props, new ResilienceFactory())
                .requiredSkillTags(wanted);

        assertThat(tags).containsExactly("plumbing", "tap-repair");
        assertThat(paths).containsExactly("/catalog/categories");
    }

    @Test
    void catalog_subcategoryAbsentFromActiveListing_isPermanent() {
        bodyToReturn = "[{\"id\":\"" + UUID.randomUUID() + "\",\"subcategories\":[]}]";
        UUID missing = UUID.randomUUID();
        HttpCatalogSkillsAdapter adapter =
                new HttpCatalogSkillsAdapter(baseUrl, new ResilienceFactory(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.requiredSkillTags(missing))
                .isInstanceOf(UnresolvableBookingException.class)
                .hasMessageContaining(missing.toString());
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void catalog_serverError_isRetriedThenReportedUnavailable() {
        statusToReturn = 500;
        bodyToReturn = "{\"error\":\"boom\"}";
        HttpCatalogSkillsAdapter adapter =
                new HttpCatalogSkillsAdapter(baseUrl, new ResilienceFactory(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> adapter.requiredSkillTags(UUID.randomUUID()))
                .isInstanceOf(EnrichmentUnavailableException.class);
        assertThat(requests.get()).isEqualTo(ResilienceFactory.MAX_ATTEMPTS);
    }

    // ---- Spring wiring -------------------------------------------------------------------------

    @Test
    void enrichmentBeans_wireUnderSpringWithTheSharedCredential() {
        // Both adapters have a second, test-only constructor; Spring must still pick the right one.
        new ApplicationContextRunner()
                .withPropertyValues("homefix.dispatch.internal-api-key=" + KEY)
                .withBean(DispatchClientProperties.class)
                .withBean(ResilienceFactory.class)
                .withUserConfiguration(HttpCustomerAddressAdapter.class, HttpCatalogSkillsAdapter.class,
                        BookingEnrichmentService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BookingEnrichmentService.class);
                });
    }
}
