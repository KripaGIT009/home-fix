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
 * Consumer-driven message contract for a booking-lifecycle event the Booking Service publishes
 * (Requirement 7.5, 22.1) and the Notification Service consumes to address and render a
 * notification (Requirement 17.5; design "Contract Tests (Pact)"). Modelled on {@code
 * BookingCreated}, whose payload the Notification Service reads into its {@code
 * LifecycleEventPayload}.
 *
 * <p>Only the recipient-addressing and rendering attributes the Notification Service depends on
 * are pinned; the Notification Service ignores unknown fields, so the Booking Service is free to
 * carry additional data. The generated pact is written to
 * {@code target/pacts/booking-service-notification-service.json} and verified by
 * {@code notification-service}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "notification-service", providerType = ProviderType.ASYNCH,
        pactVersion = PactSpecVersion.V3)
class NotificationServiceConsumerPactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Pact(consumer = "booking-service", provider = "notification-service")
    MessagePact bookingLifecyclePact(MessagePactBuilder builder) {
        PactDslJsonBody body = new PactDslJsonBody()
                .uuid("bookingId")
                .stringType("bookingReference", "HF-2024-000123")
                .uuid("customerId")
                .stringType("mobileNumber", "+919812345678")
                .stringType("emailAddress", "customer@example.com");

        return builder
                .given("a booking-lifecycle event is emitted for a customer")
                .expectsToReceive("a booking-lifecycle notification event")
                .withContent(body)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "bookingLifecyclePact")
    void notificationServiceCanConsumeLifecycleEvent(List<Message> messages) throws Exception {
        assertThat(messages).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = MAPPER.readValue(
                messages.get(0).contentsAsBytes(), Map.class);

        // The Notification Service addresses the recipient and renders message text from these.
        assertThat(payload).containsKeys(
                "bookingId", "bookingReference", "customerId", "mobileNumber", "emailAddress");
    }
}
