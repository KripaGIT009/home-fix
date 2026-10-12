package com.homefix.provider.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.provider.service.ProviderException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link HttpCatalogClientAdapter} against a loopback stub of the Service Catalog's public
 * {@code GET /catalog/categories}: only what the listing contains is active, one listing answers
 * several lookups, and an unavailable catalog is a 503, never "inactive" and never "active".
 */
class HttpCatalogClientAdapterTest {

    private static final UUID PLUMBING = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LEAK_REPAIR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INACTIVE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "[{\"id\":\"" + PLUMBING + "\",\"name\":\"Plumbing\",\"subcategories\":"
            + "[{\"id\":\"" + LEAK_REPAIR + "\",\"name\":\"Leak repair\",\"basePrice\":299.00}]}]";
    private HttpCatalogClientAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] reply = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        adapter = new HttpCatalogClientAdapter(new ResilienceFactory(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aListedCategoryIsActive_andOneAbsentFromTheListingIsNot() {
        assertThat(adapter.isCategoryActive(PLUMBING)).isTrue();
        assertThat(adapter.isCategoryActive(INACTIVE)).isFalse();
        assertThat(adapter.isCategoryActive(null)).isFalse();
        assertThat(paths).first().isEqualTo("GET /catalog/categories");
    }

    @Test
    void aSubcategoryIsActiveOnlyUnderItsOwnListedCategory() {
        assertThat(adapter.isSubcategoryActive(PLUMBING, LEAK_REPAIR)).isTrue();
        assertThat(adapter.isSubcategoryActive(PLUMBING, INACTIVE)).isFalse();
        assertThat(adapter.isSubcategoryActive(INACTIVE, LEAK_REPAIR)).isFalse();
        assertThat(adapter.isSubcategoryActive(PLUMBING, null)).isFalse();
    }

    @Test
    void oneListingAnswersSeveralLookups() {
        adapter.isCategoryActive(PLUMBING);
        adapter.isCategoryActive(INACTIVE);
        adapter.isSubcategoryActive(PLUMBING, LEAK_REPAIR);

        assertThat(paths).hasSize(1);
    }

    @Test
    void anUnavailableCatalogIs503_notAnInactiveCategory() {
        status = 500;
        body = "{}";

        assertThatThrownBy(() -> adapter.isCategoryActive(PLUMBING))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("CATALOG_UNAVAILABLE");
                });
    }

    @Test
    void anUnreachableCatalogIs503() {
        server.stop(0);

        assertThatThrownBy(() -> adapter.isCategoryActive(PLUMBING))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("CATALOG_UNAVAILABLE"));
    }
}
