package com.homefix.chat.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.homefix.chat.config.ChatProperties;
import com.homefix.chat.delivery.MessageDeliveryPort;
import com.homefix.chat.domain.ChatChannel;
import com.homefix.chat.presence.PresenceRegistry;
import com.homefix.chat.push.NotificationPushPort;
import com.homefix.chat.service.ChatService;
import com.homefix.chat.support.TestDoubles.FixedPresenceRegistry;
import com.homefix.chat.support.TestDoubles.InMemoryChatStore;
import com.homefix.chat.support.TestDoubles.RecordingDeliveryPort;
import com.homefix.chat.support.TestDoubles.RecordingPushPort;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;

/**
 * Tests that the Kafka lifecycle consumer maps each topic to the correct channel operation
 * (Requirement 18.1, 18.5). The dedup/retry/DLQ machinery of the shared base class is exercised in
 * the shared-outbox module's own tests, so here we drive the consumer's {@code handle} directly to
 * assert the topic-to-action mapping and payload handling.
 */
class ChannelLifecycleConsumerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final DlqForwarder dlq = mock(DlqForwarder.class);

    /** Test subclass exposing the protected {@code handle} hook for direct invocation. */
    private static final class TestableConsumer extends ChannelLifecycleConsumer {
        TestableConsumer(ProcessedEventRepository p, DlqForwarder d, ChatService c,
                         ObjectMapper m, Clock clk, ChatProperties props) {
            super(p, d, c, m, clk, props);
        }

        void invoke(ConsumerRecord<String, String> record) {
            handle(record);
        }
    }

    private TestableConsumer consumer(ChatService service) {
        return new TestableConsumer(processed, dlq, service, MAPPER, CLOCK, new ChatProperties());
    }

    private ChatService service(InMemoryChatStore store, RecordingDeliveryPort delivery,
                                RecordingPushPort push, PresenceRegistry presence) {
        return new ChatService(store, delivery, presence, push, Duration.ofDays(90), CLOCK);
    }

    private static ConsumerRecord<String, String> record(String topic, String json) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(topic, 0, 0L, "key", json);
        record.headers().add(new RecordHeader("eventId",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    @Test
    void providerAcceptedActivatesChannel() throws Exception {
        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(),
                FixedPresenceRegistry.allOnline());
        TestableConsumer consumer = consumer(svc);

        UUID bookingId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String json = MAPPER.writeValueAsString(new LifecycleEventPayload(
                bookingId, customerId, providerId, Instant.parse("2024-05-30T09:00:00Z")));

        consumer.invoke(record("ProviderAccepted", json));

        assertThat(store.findChannel(bookingId)).get()
                .satisfies(c -> {
                    assertThat(c.isActive()).isTrue();
                    assertThat(c.getCustomerId()).isEqualTo(customerId);
                    assertThat(c.getProviderId()).isEqualTo(providerId);
                    assertThat(c.getBookingCreatedAt())
                            .isEqualTo(Instant.parse("2024-05-30T09:00:00Z"));
                });
    }

    @Test
    void paymentCompletedDeactivatesChannel() throws Exception {
        InMemoryChatStore store = new InMemoryChatStore();
        UUID bookingId = UUID.randomUUID();
        store.saveChannel(ChatChannel.activate(bookingId, UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2024-05-30T09:00:00Z"), Instant.parse("2024-05-30T09:00:00Z")));
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(),
                FixedPresenceRegistry.allOnline());
        TestableConsumer consumer = consumer(svc);

        String json = MAPPER.writeValueAsString(
                new LifecycleEventPayload(bookingId, null, null, null));
        consumer.invoke(record("PaymentCompleted", json));

        assertThat(store.findChannel(bookingId)).get()
                .satisfies(c -> assertThat(c.isActive()).isFalse());
    }

    @Test
    void bookingCancelledDeactivatesChannel() throws Exception {
        InMemoryChatStore store = new InMemoryChatStore();
        UUID bookingId = UUID.randomUUID();
        store.saveChannel(ChatChannel.activate(bookingId, UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2024-05-30T09:00:00Z"), Instant.parse("2024-05-30T09:00:00Z")));
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(),
                FixedPresenceRegistry.allOnline());
        TestableConsumer consumer = consumer(svc);

        String json = MAPPER.writeValueAsString(
                new LifecycleEventPayload(bookingId, null, null, null));
        consumer.invoke(record("BookingCancelled", json));

        assertThat(store.findChannel(bookingId)).get()
                .satisfies(c -> assertThat(c.isActive()).isFalse());
    }

    @Test
    void providerAcceptedWithoutParticipantsIsRejectedAsPoison() throws Exception {
        InMemoryChatStore store = new InMemoryChatStore();
        ChatService svc = service(store, new RecordingDeliveryPort(), new RecordingPushPort(),
                FixedPresenceRegistry.allOnline());
        TestableConsumer consumer = consumer(svc);

        String json = MAPPER.writeValueAsString(
                new LifecycleEventPayload(UUID.randomUUID(), null, null, null));

        // A missing customer/provider on activation is unprocessable; handle throws so the shared
        // base class routes it to the DLQ after retries.
        assertThatThrownBy(() -> consumer.invoke(record("ProviderAccepted", json)))
                .isInstanceOf(IllegalStateException.class);
    }
}
