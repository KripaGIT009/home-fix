package com.homefix.notification.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.homefix.notification.config.NotificationProperties;
import com.homefix.notification.delivery.NotificationDeliveryService;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests {@link LifecycleEventConsumer}: each lifecycle listener resolves its event type from the
 * topic index and dispatches to the {@link NotificationDeliveryService} (Requirements 17.4, 17.5);
 * a duplicate event id is discarded (Requirement 22.5); and an unknown topic is treated as poison.
 */
@ExtendWith(MockitoExtension.class)
class LifecycleEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;
    @Mock
    private NotificationEventMapper eventMapper;
    @Mock
    private NotificationDeliveryService deliveryService;

    private LifecycleEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new LifecycleEventConsumer(processedEventRepository, dlqForwarder, eventMapper,
                deliveryService, new NotificationProperties());
    }

    private ConsumerRecord<String, String> record(String topic, UUID eventId) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, "key", "{}");
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private NotificationEvent event(NotificationEventType type) {
        return new NotificationEvent(type, UUID.randomUUID(), UUID.randomUUID(),
                new NotificationContact("+911", "a@b.c", "token"), Map.of());
    }

    @Test
    void bookingCreatedListener_mapsAndDispatches() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);
        when(eventMapper.map(eq(NotificationEventType.BOOKING_CREATED), any()))
                .thenReturn(event(NotificationEventType.BOOKING_CREATED));

        consumer.onBookingCreated(record("BookingCreated", eventId));

        verify(deliveryService).dispatch(any(NotificationEvent.class));
        verify(processedEventRepository).save(any());
    }

    @Test
    void multipleLifecycleListeners_eachDispatchTheirEventType() {
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), any()))
                .thenReturn(false);
        when(eventMapper.map(any(), any())).thenAnswer(inv -> event(inv.getArgument(0)));

        consumer.onProviderAssigned(record("ProviderAssigned", UUID.randomUUID()));
        consumer.onProviderAccepted(record("ProviderAccepted", UUID.randomUUID()));
        consumer.onProviderRejected(record("ProviderRejected", UUID.randomUUID()));
        consumer.onProviderArriving(record("ProviderArriving", UUID.randomUUID()));
        consumer.onProviderArrived(record("ProviderArrived", UUID.randomUUID()));
        consumer.onJobStarted(record("JobStarted", UUID.randomUUID()));
        consumer.onJobCompleted(record("JobCompleted", UUID.randomUUID()));
        consumer.onPaymentCompleted(record("PaymentCompleted", UUID.randomUUID()));
        consumer.onBookingCancelled(record("BookingCancelled", UUID.randomUUID()));
        consumer.onReviewSubmitted(record("ReviewSubmitted", UUID.randomUUID()));

        verify(deliveryService, org.mockito.Mockito.times(10)).dispatch(any(NotificationEvent.class));
    }

    @Test
    void duplicateEventId_isDiscardedWithoutDispatch() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onBookingCreated(record("BookingCreated", eventId));

        verify(deliveryService, never()).dispatch(any());
    }

    @Test
    void unknownTopic_isTreatedAsPoisonAndDeadLettered() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(false);

        // A record whose topic is not in the index cannot be mapped -> poison -> DLQ.
        consumer.onBookingCreated(record("UnknownTopic", eventId));

        verify(deliveryService, never()).dispatch(any());
        verify(dlqForwarder).forward(eq("UnknownTopic"), eq("key"), eq(eventId.toString()), anyString(),
                anyString());
    }
}
