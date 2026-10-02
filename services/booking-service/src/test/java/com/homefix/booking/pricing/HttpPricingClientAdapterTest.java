package com.homefix.booking.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.booking.client.BearerTokenRelay;
import com.homefix.shared.resilience.ResilienceFactory;
import com.sun.net.httpserver.HttpServer;

/**
 * Tests the payload {@link HttpPricingClientAdapter} sends to {@code POST /pricing/estimate},
 * against a loopback stub server. The coupon is the field that matters here: the customer is
 * shown a discounted estimate, so the booking must be priced with the same code or it is
 * charged the undiscounted total (Requirement 6.10).
 */
class HttpPricingClientAdapterTest {

    private static final String BREAKDOWN = "{\"basePrice\":500.00,\"platformFee\":55.00,"
            + "\"taxes\":99.00,\"couponAmount\":65.40,\"total\":588.60}";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private volatile String lastBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pricing/estimate", exchange -> {
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = BREAKDOWN.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpPricingClientAdapter adapter() {
        return new HttpPricingClientAdapter("http://127.0.0.1:" + server.getAddress().getPort(),
                "UTC", new BearerTokenRelay(), new ResilienceFactory());
    }

    @Test
    void estimateSendsTheCouponAndCustomer() throws Exception {
        UUID customer = UUID.randomUUID();

        PriceEstimate estimate = adapter().estimate(new PriceEstimateRequest(UUID.randomUUID(),
                UUID.randomUUID(), customer, false, Instant.parse("2024-06-15T10:00:00Z"), "SAVE10"));

        JsonNode sent = mapper.readTree(lastBody);
        assertThat(sent.path("couponCode").asText()).isEqualTo("SAVE10");
        assertThat(sent.path("userId").asText()).isEqualTo(customer.toString());
        assertThat(estimate.total()).isEqualByComparingTo("588.60");
        assertThat(estimate.components()).containsEntry("couponAmount", new java.math.BigDecimal("65.40"));
    }

    @Test
    void estimateWithoutCouponSendsNone() throws Exception {
        adapter().estimate(new PriceEstimateRequest(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), true, null, null));

        JsonNode sent = mapper.readTree(lastBody);
        assertThat(sent.path("couponCode").isMissingNode() || sent.path("couponCode").isNull()).isTrue();
    }
}
