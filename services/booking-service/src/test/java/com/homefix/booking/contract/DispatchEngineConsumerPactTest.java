package com.homefix.booking.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.ObjectMapper;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.annotations.Pact;
import au.com.dius.pact.core.model.messaging.Message;
import au.com.dius.pact.core.model.messaging.MessagePact;

/**
 * Consumer-driven message contract for the {@code BookingCreated} Kafka event the Booking
 * Service publishes when a booking enters SEARCHING_PROVIDER (Requirement 7.5) and the Dispatch
 * Engine consumes to begin provider matching (Requirement 8.2, 22.5; design "Contract Tests
 * (Pact)"). The contract pins the fields the Dispatch Engine relies on so the Booking Service
 * cannot drop or rename one without a provider-verification failure.
 *
 * <p>Because the transport is asynchronous, this is a Pact <em>message</em> contract rather than
 * an HTTP one. The generated pact is written to
 * {@code target/pacts/booking-service-dispatch-engine.json} and verified by {@code dispatch-engine}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "dispatch-engine", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class DispatchEngineConsumerPactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Pact(consumer = "booking-service", provider = "dispatch-engine")
    MessagePact bookingCreatedPact(MessagePactBuilder builder) {
        PactDslJsonBody body = new PactDslJsonBody()
                .uuid("bookingId")
                .uuid("customerId")
                .uuid("subcategoryId")
                .numberType("customerLat", 12.9716)
                .numberType("customerLon", 77.5946)
                .booleanType("emergency", false)
                .array("requiredSkillTags")
                    .stringType("plumbing")
                .closeArray()
                .asBody();

        return builder
                .given("a booking has been created and is searching for a provider")
                .expectsToReceive("a BookingCreated event")
                .withContent(body)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "bookingCreatedPact")
    void dispatchEngineCanConsumeBookingCreated(List<Message> messages) throws Exception {
        assertThat(messages).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = MAPPER.readValue(
                messages.get(0).contentsAsBytes(), Map.class);

        // The Dispatch Engine matches a provider from these fields (DispatchRequest).
        assertThat(payload).containsKeys(
                "bookingId", "customerId", "subcategoryId",
                "customerLat", "customerLon", "requiredSkillTags", "emergency");
    }
}
