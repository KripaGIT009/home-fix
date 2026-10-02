package com.homefix.verification.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.shared.resilience.ResilienceFactory;
import com.homefix.verification.provider.ProviderDirectoryPort.ProviderSummary;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the Provider Service directory adapter against a loopback stub server: the credential,
 * method, path and query it sends, chunking, and that every failure mode — refused credential,
 * 5xx, timeout, unreachable, unconfigured key — fails soft to "no names".
 */
class HttpProviderDirectoryAdapterTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{\"providers\":[]}";
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            methods.add(exchange.getRequestMethod());
            paths.add(exchange.getRequestURI().getPath());
            queries.add(String.valueOf(exchange.getRequestURI().getQuery()));
            String key = exchange.getRequestHeaders().getFirst(HttpProviderDirectoryAdapter.INTERNAL_KEY_HEADER);
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
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpProviderDirectoryAdapter adapter() {
        return adapter(KEY, Duration.ofSeconds(2));
    }

    private HttpProviderDirectoryAdapter adapter(String key, Duration timeout) {
        return new HttpProviderDirectoryAdapter(new ResilienceFactory(), baseUrl, key, timeout);
    }

    @Test
    void returnsTheSummariesPresentingTheServiceCredential() {
        UUID named = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        bodyToReturn = "{\"providers\":[{\"id\":\"" + named
                + "\",\"displayName\":\"Ravi Kumar\",\"primarySkill\":\"plumbing\"}]}";

        Map<UUID, ProviderSummary> result = adapter().summariesOf(List.of(named, missing));

        assertThat(result).containsOnlyKeys(named);
        assertThat(result.get(named)).isEqualTo(new ProviderSummary("Ravi Kumar", "plumbing"));
        assertThat(methods).containsExactly("GET");
        assertThat(paths).containsExactly("/internal/providers/summaries");
        assertThat(queries.get(0)).contains("ids=" + named).contains("ids=" + missing);
        assertThat(presentedKeys).containsExactly(KEY);
    }

    @Test
    void providersTheResponseWasNotAskedAbout_areIgnored() {
        UUID asked = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        bodyToReturn = "{\"providers\":[{\"id\":\"" + asked + "\",\"displayName\":\"A\"},"
                + "{\"id\":\"" + stranger + "\",\"displayName\":\"B\"}]}";

        assertThat(adapter().summariesOf(List.of(asked))).containsOnlyKeys(asked);
    }

    @Test
    void largeQuestions_areSentInChunksWithinTheServersCap() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < HttpProviderDirectoryAdapter.MAX_IDS_PER_REQUEST + 5; i++) {
            ids.add(UUID.randomUUID());
        }

        adapter().summariesOf(ids);

        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void emptyQuestion_makesNoCall() {
        assertThat(adapter().summariesOf(List.of())).isEmpty();
        assertThat(requests.get()).isZero();
    }

    @Test
    void refusedCredential_failsSoftWithoutRetrying() {
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_AUTH_FAILED\",\"message\":\"Invalid internal credentials\"}";

        assertThat(adapter().summariesOf(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void serverError_isRetriedThenFailsSoft() {
        statusToReturn = 503;
        bodyToReturn = "{}";

        assertThat(adapter().summariesOf(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(requests.get()).isEqualTo(ResilienceFactory.MAX_ATTEMPTS);
    }

    @Test
    void slowResponse_timesOutAndFailsSoft() {
        delayMillis = 1_000;

        assertThat(adapter(KEY, Duration.ofMillis(200)).summariesOf(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void unreachableService_failsSoft() {
        server.stop(0);

        assertThat(adapter().summariesOf(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void unconfiguredKey_failsSoftWithoutCalling() {
        assertThat(adapter(" ", Duration.ofSeconds(2)).summariesOf(List.of(UUID.randomUUID()))).isEmpty();
        assertThat(requests.get()).isZero();
    }
}
