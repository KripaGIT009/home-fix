package com.homefix.booking.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests {@link HttpCatalogClientAdapter} against a loopback stub of the catalog's
 * {@code GET /catalog/categories}: subcategory names are read from the same listing that answers
 * bookability, and an unavailable catalog degrades the names to an empty map rather than throwing,
 * because the booking read endpoints use them only as labels.
 */
class HttpCatalogClientAdapterTest {

    private static final UUID CATEGORY = UUID.fromString("3f1a6c2e-9b4d-4f7a-8c21-5d0e7a9b1c34");
    private static final UUID TAP_REPAIR = UUID.fromString("7c9e4b1a-2d86-4a3f-9e51-8b2c6d4f0a77");
    private static final UUID DRAIN = UUID.fromString("a41d8f62-5c73-4e19-b8a0-62f3d9e1c405");

    /** The catalog's real shape, extra fields included, which the adapter must ignore. */
    private static final String CATALOG = "[{\"id\":\"" + CATEGORY + "\",\"name\":\"Plumbing\","
            + "\"displayOrder\":1,\"subcategories\":["
            + "{\"id\":\"" + TAP_REPAIR + "\",\"categoryId\":\"" + CATEGORY + "\",\"name\":\"Tap repair\","
            + "\"basePrice\":499.00,\"estimatedDurationMin\":45,\"skillTags\":[],\"emergencyAvailable\":true},"
            + "{\"id\":\"" + DRAIN + "\",\"categoryId\":\"" + CATEGORY + "\",\"name\":\"\"}]},"
            + "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Empty\",\"subcategories\":null}]";

    private HttpServer server;
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/catalog/categories", exchange -> {
            byte[] body = (status == 200 ? CATALOG : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpCatalogClientAdapter adapter() {
        return new HttpCatalogClientAdapter("http://127.0.0.1:" + server.getAddress().getPort(),
                new ResilienceFactory());
    }

    @Test
    void subcategoryNamesAreReadFromTheActiveListing() {
        // A blank name is left out, so callers fall back exactly as for a missing subcategory.
        assertThat(adapter().subcategoryNames()).containsExactly(
                java.util.Map.entry(TAP_REPAIR, "Tap repair"));
    }

    @Test
    void unavailableCatalogGivesNoNamesInsteadOfFailing() {
        status = 503;

        assertThat(adapter().subcategoryNames()).isEmpty();
    }

    @Test
    void bookabilityStillReadsTheSameListing() {
        HttpCatalogClientAdapter adapter = adapter();

        assertThat(adapter.isSubcategoryActive(CATEGORY, TAP_REPAIR)).isTrue();
        assertThat(adapter.isSubcategoryActive(CATEGORY, UUID.randomUUID())).isFalse();
    }
}
