package com.homefix.payment.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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

import com.homefix.payment.service.PaymentException;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the Booking Service adapter against a loopback stub server: the paths, method, credential
 * and body it sends; the facts it reads; 404 and 409 answered as results without a retry; and that
 * every way the Booking Service can fail to answer (5xx, refused credential, timeout, unreachable,
 * unconfigured key) fails closed as {@code 503 BOOKING_SERVICE_UNAVAILABLE}.
 */
class HttpBookingClientAdapterTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private final List<String> presentedKeys = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "";
    private volatile long delayMillis = 0;

    private final UUID bookingId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            methods.add(exchange.getRequestMethod());
            String key = exchange.getRequestHeaders().getFirst(HttpBookingClientAdapter.INTERNAL_KEY_HEADER);
            presentedKeys.add(key == null ? "<none>" : key);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

    private HttpBookingClientAdapter adapter() {
        return new HttpBookingClientAdapter(new ResilienceFactory(), baseUrl, KEY, Duration.ofSeconds(2));
    }

    private String factsJson(String status) {
        return """
                {"bookingId":"%s","reference":"HF-1001","customerId":"%s","providerId":"%s",
                 "status":"%s","amount":499.00,"currency":"INR"}
                """.formatted(bookingId, customerId, providerId, status);
    }

    private static void assertPaymentError(Throwable ex, HttpStatus status, String code) {
        assertThat(ex).isInstanceOf(PaymentException.class);
        PaymentException pe = (PaymentException) ex;
        assertThat(pe.getStatus()).isEqualTo(status);
        assertThat(pe.getErrorCode()).isEqualTo(code);
    }

    // ------------------------------------------------------------------ payment-facts

    @Test
    void paymentFacts_readsTheFacts_presentingTheServiceCredential() {
        bodyToReturn = factsJson("JOB_COMPLETED");

        Optional<BookingPaymentFacts> facts = adapter().paymentFacts(bookingId);

        assertThat(facts).hasValueSatisfying(f -> {
            assertThat(f.bookingId()).isEqualTo(bookingId);
            assertThat(f.customerId()).isEqualTo(customerId);
            assertThat(f.providerId()).isEqualTo(providerId);
            assertThat(f.status()).isEqualTo("JOB_COMPLETED");
            assertThat(f.amount()).isEqualByComparingTo(new BigDecimal("499.00"));
            assertThat(f.currency()).isEqualTo("INR");
            assertThat(f.isPayable()).isTrue();
        });
        assertThat(methods).containsExactly("GET");
        assertThat(paths).containsExactly("/internal/bookings/" + bookingId + "/payment-facts");
        assertThat(presentedKeys).containsExactly(KEY);
    }

    @Test
    void paymentFacts_404_isNotFound_withoutARetry() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"BOOKING_NOT_FOUND\",\"message\":\"nope\"}";

        assertThat(adapter().paymentFacts(bookingId)).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void paymentFacts_5xx_isRetried_thenFailsClosedAs503() {
        statusToReturn = 500;
        bodyToReturn = "{\"errorCode\":\"INTERNAL_ERROR\"}";

        assertThatThrownBy(() -> adapter().paymentFacts(bookingId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThat(requests.get()).isEqualTo(ResilienceFactory.MAX_ATTEMPTS);
    }

    @Test
    void paymentFacts_refusedCredential_is503_withoutARetry() {
        statusToReturn = 401;
        bodyToReturn = "{\"errorCode\":\"UNAUTHORIZED\"}";

        assertThatThrownBy(() -> adapter().paymentFacts(bookingId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void paymentFacts_timeout_is503() {
        delayMillis = 1500;
        bodyToReturn = factsJson("JOB_COMPLETED");
        HttpBookingClientAdapter slow = new HttpBookingClientAdapter(
                new ResilienceFactory(), baseUrl, KEY, Duration.ofMillis(300));

        assertThatThrownBy(() -> slow.paymentFacts(bookingId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
    }

    @Test
    void paymentFacts_unreachable_is503() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        HttpBookingClientAdapter nowhere = new HttpBookingClientAdapter(
                new ResilienceFactory(), "http://127.0.0.1:" + freePort, KEY, Duration.ofMillis(500));

        assertThatThrownBy(() -> nowhere.paymentFacts(bookingId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
    }

    @Test
    void unconfiguredKey_is503_withoutCallingTheBookingService() {
        HttpBookingClientAdapter unconfigured = new HttpBookingClientAdapter(
                new ResilienceFactory(), baseUrl, "", Duration.ofSeconds(2));

        assertThatThrownBy(() -> unconfigured.paymentFacts(bookingId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThatThrownBy(() -> unconfigured.markPaymentPending(bookingId, customerId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThat(requests.get()).isZero();
    }

    // ------------------------------------------------------------------ payment-pending

    @Test
    void markPaymentPending_postsTheCustomer_andReturnsTheFacts() {
        bodyToReturn = factsJson("PAYMENT_PENDING");

        BookingPaymentFacts facts = adapter().markPaymentPending(bookingId, customerId);

        assertThat(facts.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(methods).containsExactly("POST");
        assertThat(paths).containsExactly("/internal/bookings/" + bookingId + "/payment-pending");
        assertThat(presentedKeys).containsExactly(KEY);
        assertThat(bodies.get(0)).isEqualTo("{\"customerId\":\"" + customerId + "\"}");
    }

    @Test
    void markPaymentPending_409_isNotPayable_withoutARetry() {
        statusToReturn = 409;
        bodyToReturn = "{\"errorCode\":\"BOOKING_NOT_PAYABLE\"}";

        assertThatThrownBy(() -> adapter().markPaymentPending(bookingId, customerId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.CONFLICT, "BOOKING_NOT_PAYABLE"));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void markPaymentPending_404_isNotFound() {
        statusToReturn = 404;
        bodyToReturn = "{\"errorCode\":\"BOOKING_NOT_FOUND\"}";

        assertThatThrownBy(() -> adapter().markPaymentPending(bookingId, customerId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND"));
        assertThat(requests.get()).isEqualTo(1);
    }

    /** The POST is idempotent on the Booking Service side, so a transient failure is retried. */
    @Test
    void markPaymentPending_5xx_isRetried_thenFailsClosedAs503() {
        statusToReturn = 503;

        assertThatThrownBy(() -> adapter().markPaymentPending(bookingId, customerId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
        assertThat(requests.get()).isEqualTo(ResilienceFactory.MAX_ATTEMPTS);
    }

    @Test
    void markPaymentPending_emptyBody_is503() {
        statusToReturn = 200;
        bodyToReturn = "";

        assertThatThrownBy(() -> adapter().markPaymentPending(bookingId, customerId))
                .satisfies(ex -> assertPaymentError(ex, HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE"));
    }
}
