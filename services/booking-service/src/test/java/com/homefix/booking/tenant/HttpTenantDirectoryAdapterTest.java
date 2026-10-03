package com.homefix.booking.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.booking.tenant.TenantDirectoryPort.CoveringTenant;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantMembership;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantRef;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantSummary;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link HttpTenantDirectoryAdapter} against a loopback stub of provider-service's
 * {@code /internal/tenants/**} endpoints, in the JSON shapes of the design's contract: every call
 * presents the internal key, a 404 is an empty answer (no retry), and a 5xx or refused key is an
 * outage the caller sees as {@link TenantDirectoryUnavailableException}, never as "no Tenant".
 */
class HttpTenantDirectoryAdapterTest {

    private static final String KEY = "internal-test-key";
    private static final UUID TENANT = UUID.fromString("0b7d4f1e-3c2a-4e8b-9f61-2a5c7d9e1b30");
    private static final UUID OTHER = UUID.fromString("5e2c9a7b-1d34-4f86-a0b2-7c9e3d1f4a52");
    private static final UUID PROVIDER = UUID.fromString("9a1f3c5e-7b2d-4c8a-b6e4-1d3f5a7c9e02");
    private static final UUID ADMIN = UUID.fromString("2c4e6a8b-0d1f-4a3c-8e5b-7f9a1c3e5d70");
    private static final UUID CATEGORY = UUID.fromString("3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34");

    private HttpServer server;
    /** path -> (status, body); unknown paths answer 404. */
    private final Map<String, Object[]> routes = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tenants", exchange -> {
            requests.incrementAndGet();
            lastKey.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            lastQuery.set(exchange.getRequestURI().getQuery());
            Object[] route = routes.getOrDefault(exchange.getRequestURI().getPath(),
                    new Object[]{404, "{\"errorCode\":\"TENANT_NOT_FOUND\",\"message\":\"no\"}"});
            byte[] body = ((String) route[1]).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders((int) route[0], body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpTenantDirectoryAdapter adapter() {
        return new HttpTenantDirectoryAdapter("http://127.0.0.1:" + server.getAddress().getPort(), KEY,
                new ResilienceFactory());
    }

    private void route(String path, int status, String body) {
        routes.put(path, new Object[]{status, body});
    }

    @Test
    void coveringReadsTheTenantsNearestFirstWithTheInternalKey() {
        route("/internal/tenants/covering", 200, "{\"tenants\":["
                + "{\"tenantId\":\"" + TENANT + "\",\"name\":\"Ara Home Services\",\"distanceKm\":1.2},"
                + "{\"tenantId\":\"" + OTHER + "\",\"name\":\"Bhojpur Fixers\",\"distanceKm\":7.9,\"extra\":true}]}");

        assertThat(adapter().covering(25.556, 84.663, CATEGORY)).containsExactly(
                new CoveringTenant(TENANT, "Ara Home Services", 1.2),
                new CoveringTenant(OTHER, "Bhojpur Fixers", 7.9));
        assertThat(lastKey.get()).isEqualTo(KEY);
        assertThat(lastQuery.get()).contains("lat=25.556").contains("lon=84.663")
                .contains("categoryId=" + CATEGORY);
    }

    @Test
    void noCoveringTenantIsAnEmptyList() {
        route("/internal/tenants/covering", 200, "{\"tenants\":[]}");

        assertThat(adapter().covering(25.5, 84.6, CATEGORY)).isEmpty();
    }

    @Test
    void anOutageIsNotMistakenForNoCoverage() {
        route("/internal/tenants/covering", 503, "{}");

        assertThatThrownBy(() -> adapter().covering(25.5, 84.6, CATEGORY))
                .isInstanceOf(TenantDirectoryUnavailableException.class);
    }

    @Test
    void aRefusedInternalKeyIsAnOutageAndIsNotRetried() {
        route("/internal/tenants/by-admin/" + ADMIN, 401, "{\"errorCode\":\"INTERNAL_AUTH_FAILED\"}");

        assertThatThrownBy(() -> adapter().byAdmin(ADMIN))
                .isInstanceOf(TenantDirectoryUnavailableException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void byAdminReadsTheTenantAndAnswersEmptyOn404WithoutRetrying() {
        route("/internal/tenants/by-admin/" + ADMIN, 200,
                "{\"tenantId\":\"" + TENANT + "\",\"name\":\"Ara Home Services\",\"status\":\"SUSPENDED\"}");
        HttpTenantDirectoryAdapter adapter = adapter();

        TenantSummary tenant = adapter.byAdmin(ADMIN).orElseThrow();
        assertThat(tenant).isEqualTo(new TenantSummary(TENANT, "Ara Home Services", "SUSPENDED"));
        assertThat(tenant.suspended()).isTrue();

        requests.set(0);
        assertThat(adapter.byAdmin(UUID.randomUUID())).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void membershipReadsAssignabilityAndAnswersEmptyForANonMember() {
        route("/internal/tenants/" + TENANT + "/providers/" + PROVIDER, 200,
                "{\"member\":true,\"assignable\":false,\"verificationStatus\":\"PENDING\"}");
        HttpTenantDirectoryAdapter adapter = adapter();

        assertThat(adapter.membership(TENANT, PROVIDER))
                .contains(new TenantMembership(true, false, "PENDING"));
        assertThat(adapter.membership(OTHER, PROVIDER)).isEmpty();
    }

    @Test
    void ofProviderAndByIdReadTheTenant() {
        route("/internal/tenants/of-provider/" + PROVIDER, 200,
                "{\"tenantId\":\"" + TENANT + "\",\"name\":\"Ara Home Services\"}");
        route("/internal/tenants/" + TENANT, 200,
                "{\"tenantId\":\"" + TENANT + "\",\"name\":\"Ara Home Services\",\"status\":\"ACTIVE\"}");
        HttpTenantDirectoryAdapter adapter = adapter();

        assertThat(adapter.ofProvider(PROVIDER)).contains(new TenantRef(TENANT, "Ara Home Services"));
        assertThat(adapter.ofProvider(UUID.randomUUID())).isEmpty();
        assertThat(adapter.byId(TENANT)).contains(new TenantSummary(TENANT, "Ara Home Services", "ACTIVE"));
        assertThat(adapter.byId(OTHER)).isEmpty();
    }
}
