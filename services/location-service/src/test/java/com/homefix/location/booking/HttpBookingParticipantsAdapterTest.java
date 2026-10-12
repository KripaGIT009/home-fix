package com.homefix.location.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homefix.location.service.LocationException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the Booking Service participant lookup against a loopback stub server: the path and
 * credential it sends, the participants it reads (ignoring the payment fields it does not need), a
 * 404 answered as empty without a retry, and that a refused credential or a missing key fails closed
 * as {@code 503}.
 */
class HttpBookingParticipantsAdapterTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "";

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            String key = exchange.getRequestHeaders().getFirst(HttpBookingParticipantsAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
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

    private HttpBookingParticipantsAdapter adapter(String key) {
        return new HttpBookingParticipantsAdapter(new ResilienceFactory(), baseUrl, key, Duration.ofSeconds(2));
    }

    @Test
    void readsTheParticipantsFromThePaymentFactsEndpointWithTheInternalKey() {
        bodyToReturn = """
                {"bookingId":"%s","reference":"HF-1","customerId":"%s","providerId":"%s",
                 "status":"PROVIDER_EN_ROUTE","amount":499.00,"currency":"INR"}
                """.formatted(bookingId, customerId, providerId);

        Optional<BookingParticipants> participants = adapter(KEY).participants(bookingId);

        assertThat(participants).contains(
                new BookingParticipants(bookingId, customerId, providerId, "PROVIDER_EN_ROUTE"));
        assertThat(paths).containsExactly("/internal/bookings/" + bookingId + "/payment-facts");
        assertThat(presentedKeys).containsExactly(KEY);
    }

    @Test
    void unknownBooking_isEmpty_andNotRetried() {
        statusToReturn = 404;

        assertThat(adapter(KEY).participants(bookingId)).isEmpty();
        assertThat(requests).hasValue(1);
    }

    @Test
    void refusedCredential_failsClosed() {
        statusToReturn = 401;

        assertThatThrownBy(() -> adapter(KEY).participants(bookingId))
                .isInstanceOfSatisfying(LocationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void missingKey_failsClosedWithoutCallingTheBookingService() {
        assertThatThrownBy(() -> adapter(" ").participants(bookingId))
                .isInstanceOfSatisfying(LocationException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(requests).hasValue(0);
    }
}
