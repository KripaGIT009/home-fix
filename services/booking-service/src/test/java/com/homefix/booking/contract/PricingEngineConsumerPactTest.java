package com.homefix.booking.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;

/**
 * Consumer-driven HTTP contract for the Booking Service ({@code booking-service}) consuming the
 * Pricing Engine ({@code pricing-engine}) itemised-estimate endpoint (Requirement 7.3, 6.9;
 * design "Contract Tests (Pact)"). The Booking Service requests an estimate before asking the
 * customer to confirm a booking; this pact pins the request/response schema so the Pricing
 * Engine cannot break the estimate contract without a verification failure.
 *
 * <p>The generated pact is written to {@code target/pacts/booking-service-pricing-engine.json}
 * and later verified by {@code pricing-engine}'s provider test.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "pricing-engine", pactVersion = PactSpecVersion.V3)
class PricingEngineConsumerPactTest {

    /**
     * The estimate request the Booking Service sends. Only {@code subcategoryId} is mandatory on
     * the Pricing Engine side; the Booking Service always sends the emergency and surge flags plus
     * the subcategory, and expects a fully itemised, non-null breakdown back (Property 6).
     */
    @Pact(consumer = "booking-service", provider = "pricing-engine")
    RequestResponsePact estimatePact(PactDslWithProvider builder) {
        PactDslJsonBody request = new PactDslJsonBody()
                .uuid("subcategoryId", "22222222-2222-2222-2222-222222222222")
                .booleanType("emergency", false)
                .booleanType("surgeActive", false);

        // Every component is present and numeric; the components sum to total (verified by the
        // provider against real pricing output). We use type matchers so the provider is free to
        // return any valid monetary amount, while the *shape* is locked.
        PactDslJsonBody response = new PactDslJsonBody()
                .decimalType("basePrice", 500.00)
                .decimalType("distanceCharge", 50.00)
                .decimalType("timeCharge", 0.00)
                .decimalType("partsMaterialsCharge", 0.00)
                .decimalType("emergencyCharge", 0.00)
                .decimalType("weekendSurcharge", 0.00)
                .decimalType("nightSurcharge", 0.00)
                .decimalType("demandSurgeCharge", 0.00)
                .decimalType("platformFee", 55.00)
                .decimalType("taxes", 99.00)
                .decimalType("discountAmount", 0.00)
                .decimalType("couponAmount", 0.00)
                .decimalType("total", 704.00);

        return builder
                .given("a configured subcategory with pricing parameters")
                .uponReceiving("a request for an itemised price estimate")
                .path("/pricing/estimate")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(request)
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(response)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "estimatePact")
    void bookingServiceCanObtainAnItemisedEstimate(MockServer mockServer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"subcategoryId\":\"22222222-2222-2222-2222-222222222222\","
                + "\"emergency\":false,\"surgeActive\":false}";

        @SuppressWarnings("unchecked")
        Map<String, Object> estimate = new RestTemplate().postForObject(
                mockServer.getUrl() + "/pricing/estimate",
                new HttpEntity<>(body, headers),
                Map.class);

        // The Booking Service depends on total plus the itemised components being present.
        assertThat(estimate).isNotNull();
        assertThat(estimate).containsKey("total");
        assertThat(estimate).containsKeys("basePrice", "platformFee", "taxes",
                "emergencyCharge", "demandSurgeCharge", "discountAmount", "couponAmount");
    }
}
