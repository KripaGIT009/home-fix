package com.homefix.notification.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homefix.notification.domain.NotificationContact;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Exercises {@link AuthServiceContactDirectoryAdapter} over real HTTP against a throwaway local
 * server standing in for the Auth Service, so the timeout, the credential header and each status
 * mapping are tested as they behave on the wire: 200 maps to a contact, 404 {@code USER_NOT_FOUND}
 * to a skip, and everything else (5xx, 401, an unrecognised 404, a timeout, a refused connection)
 * to a retryable {@link ContactLookupException}.
 */
class AuthServiceContactDirectoryAdapterTest {

    private static final String KEY = "test-internal-key";
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private HttpServer server;
    private final AtomicReference<Responder> responder = new AtomicReference<>();
    private final AtomicReference<String> seenKey = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    @FunctionalInterface
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst(AuthServiceContactDirectoryAdapter.INTERNAL_KEY_HEADER));
            seenPath.set(exchange.getRequestURI().getPath());
            try {
                responder.get().respond(exchange);
            } finally {
                exchange.close();
            }
        });
        // Handlers off the dispatcher thread, so a deliberately slow handler cannot delay stop().
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AuthServiceContactDirectoryAdapter adapter() {
        return new AuthServiceContactDirectoryAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(), KEY, TIMEOUT);
    }

    private static Responder json(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    @Test
    void ok_mapsAddressesAndSendsTheServiceCredentialToTheInternalPath() {
        UUID userId = UUID.randomUUID();
        responder.set(json(200, "{\"userId\":\"" + userId + "\",\"mobileNumber\":\"+919000000001\","
                + "\"emailAddress\":null,\"unknownField\":true}"));

        Optional<NotificationContact> contact = adapter().findContact(userId);

        assertThat(contact).contains(new NotificationContact("+919000000001", null, null));
        assertThat(seenKey.get()).isEqualTo(KEY);
        assertThat(seenPath.get()).isEqualTo("/internal/users/" + userId + "/contact");
    }

    @Test
    void userNotFound_isAPermanentSkip() {
        responder.set(json(404, "{\"errorCode\":\"USER_NOT_FOUND\",\"message\":\"No account\"}"));

        assertThat(adapter().findContact(UUID.randomUUID())).isEmpty();
    }

    @Test
    void notFoundWithoutTheUserNotFoundCode_isRetryable() {
        // e.g. an Auth Service build without this endpoint: must not silently skip everyone.
        responder.set(json(404, "{\"status\":404,\"error\":\"Not Found\"}"));

        assertThatThrownBy(() -> adapter().findContact(UUID.randomUUID()))
                .isInstanceOf(ContactLookupException.class)
                .hasMessageContaining("404");
    }

    @Test
    void serverError_isRetryable() {
        responder.set(json(503, "{\"errorCode\":\"UNAVAILABLE\"}"));

        assertThatThrownBy(() -> adapter().findContact(UUID.randomUUID()))
                .isInstanceOf(ContactLookupException.class)
                .hasMessageContaining("503");
    }

    @Test
    void rejectedCredential_isRetryableSoEventsWaitInTheDeadLetterTopic() {
        responder.set(json(401, "{\"errorCode\":\"INTERNAL_AUTH_FAILED\"}"));

        assertThatThrownBy(() -> adapter().findContact(UUID.randomUUID()))
                .isInstanceOf(ContactLookupException.class)
                .hasMessageContaining("401");
    }

    @Test
    void slowResponse_timesOutPromptlyAsRetryable() {
        responder.set(exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            json(200, "{\"userId\":null}").respond(exchange);
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter().findContact(UUID.randomUUID()))
                .isInstanceOf(ContactLookupException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(elapsedMs).as("bounded by the configured timeout, not the server delay").isLessThan(2_500);
    }

    @Test
    void connectionRefused_isRetryable() {
        AuthServiceContactDirectoryAdapter adapter = adapter();
        server.stop(0);

        assertThatThrownBy(() -> adapter.findContact(UUID.randomUUID()))
                .isInstanceOf(ContactLookupException.class);
    }

    @Test
    void missingCredential_failsAtStartup() {
        assertThatThrownBy(() -> new AuthServiceContactDirectoryAdapter("http://localhost", " ", TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key");
    }
}
