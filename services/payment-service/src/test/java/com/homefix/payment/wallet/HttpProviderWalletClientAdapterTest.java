package com.homefix.payment.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link HttpProviderWalletClientAdapter} against a loopback stub of the Provider Service: a
 * credit is sent to the provider's earnings endpoint with the service credential and the booking,
 * and anything but a 2xx is a {@link WalletCreditException} so the caller retries and alerts.
 */
class HttpProviderWalletClientAdapterTest {

    private static final UUID PROVIDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOOKING = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpProviderWalletClientAdapter adapter;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"providerId\":\"x\",\"walletBalance\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        adapter = new HttpProviderWalletClientAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-internal-key");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void creditsTheProvidersEarningForTheBooking() {
        adapter.creditEarning(PROVIDER, BOOKING, "HFX-1", new BigDecimal("615.25"), new BigDecimal("123.05"),
                new BigDecimal("492.20"));

        assertThat(path.get()).isEqualTo("POST /internal/providers/" + PROVIDER + "/earnings");
        assertThat(key.get()).isEqualTo("test-internal-key");
        assertThat(body.get())
                .contains("\"bookingId\":\"" + BOOKING + "\"")
                .contains("\"bookingReference\":\"HFX-1\"")
                .contains("\"gross\":615.25")
                .contains("\"platformFee\":123.05");
    }

    @Test
    void aRefusedCreditIsAWalletCreditException() {
        for (int code : new int[] {401, 404, 500, 503}) {
            status.set(code);
            assertThatThrownBy(() -> adapter.creditEarning(PROVIDER, BOOKING, null, BigDecimal.TEN, BigDecimal.ONE,
                    new BigDecimal("9")))
                    .as("HTTP %d", code)
                    .isInstanceOf(WalletCreditException.class)
                    .hasMessageContaining(BOOKING.toString());
        }
    }

    /**
     * A definite refusal (400, 404, 409, 422) is permanent, so the caller stops re-sending it; an
     * outage or a rejected service credential (fixed by an operator) is not.
     */
    @Test
    void onlyADefiniteRefusalIsPermanent() {
        for (int code : new int[] {400, 404, 409, 422}) {
            status.set(code);
            assertThatThrownBy(() -> adapter.creditEarning(PROVIDER, BOOKING, null, BigDecimal.TEN, BigDecimal.ONE,
                    new BigDecimal("9")))
                    .as("HTTP %d", code)
                    .isInstanceOfSatisfying(WalletCreditException.class, e -> assertThat(e.isPermanent()).isTrue());
        }
        for (int code : new int[] {401, 403, 408, 429, 500, 503}) {
            status.set(code);
            assertThatThrownBy(() -> adapter.creditEarning(PROVIDER, BOOKING, null, BigDecimal.TEN, BigDecimal.ONE,
                    new BigDecimal("9")))
                    .as("HTTP %d", code)
                    .isInstanceOfSatisfying(WalletCreditException.class, e -> assertThat(e.isPermanent()).isFalse());
        }
    }

    @Test
    void anUnreachableProviderServiceIsAWalletCreditException() {
        server.stop(0);

        assertThatThrownBy(() -> adapter.creditEarning(PROVIDER, BOOKING, null, BigDecimal.TEN, BigDecimal.ONE,
                new BigDecimal("9")))
                .isInstanceOf(WalletCreditException.class);
    }
}
