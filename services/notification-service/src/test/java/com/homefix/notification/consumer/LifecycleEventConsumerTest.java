package com.homefix.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.notification.config.NotificationProperties;
import com.homefix.notification.contact.RecipientResolver;
import com.homefix.notification.delivery.NotificationDeliveryService;
import com.homefix.notification.domain.NotificationAudience;
import com.homefix.notification.domain.NotificationContact;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.notification.domain.RecipientPolicy;
import com.homefix.notification.support.TestDoubles.FakeContactDirectory;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.KafkaProducerTemplate;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests {@link LifecycleEventConsumer} end to end short of delivery: each listener's topic resolves
 * its event type, the real mapper parses the payload <em>as each producer actually publishes it</em>,
 * the real {@link RecipientPolicy} decides who is notified, contact details come from the
 * (fake) directory, and one addressed notification per recipient reaches the
 * {@link NotificationDeliveryService} (Requirements 16.2, 16.3, 17.4, 17.5). Also covers the
 * failure routing: a duplicate is discarded (22.5); an unknown topic, an event naming no
 * recipient, and an unreachable contact directory are retried and dead-lettered (22.6).
 */
@ExtendWith(MockitoExtension.class)
class LifecycleEventConsumerTest {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final NotificationContact CUSTOMER_CONTACT = new NotificationContact("+919000000001", null, null);
    private static final NotificationContact PROVIDER_CONTACT = new NotificationContact("+919000000002", null, null);

    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private DlqForwarder dlqForwarder;
    @Mock
    private NotificationDeliveryService deliveryService;

    private FakeContactDirectory directory;
    private LifecycleEventConsumer consumer;

    @BeforeEach
    void setUp() {
        directory = new FakeContactDirectory().with(CUSTOMER, CUSTOMER_CONTACT).with(PROVIDER, PROVIDER_CONTACT);
        consumer = consumerWith(directory);
        lenient().when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), any()))
                .thenReturn(false);
    }

    private LifecycleEventConsumer consumerWith(FakeContactDirectory contacts) {
        return new LifecycleEventConsumer(processedEventRepository, dlqForwarder,
                new NotificationEventMapper(new ObjectMapper()),
                new RecipientResolver(new RecipientPolicy(), contacts),
                deliveryService, new NotificationProperties());
    }

    private static ConsumerRecord<String, String> record(String topic, UUID eventId, String json) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, "key", json);
        rec.headers().add(new RecordHeader(KafkaProducerTemplate.HEADER_EVENT_ID,
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return rec;
    }

    private List<NotificationEvent> dispatched() {
        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(deliveryService, atLeastOnce()).dispatch(captor.capture());
        return captor.getAllValues();
    }

    private void assertRecipients(Object... userAndAudience) {
        List<NotificationEvent> events = dispatched();
        org.assertj.core.groups.Tuple[] expected = new org.assertj.core.groups.Tuple[userAndAudience.length / 2];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = tuple(userAndAudience[2 * i], userAndAudience[2 * i + 1]);
        }
        assertThat(events)
                .extracting(NotificationEvent::recipientUserId, NotificationEvent::audience)
                .containsExactlyInAnyOrder(expected);
    }

    // ----- producer payloads as published today (booking, dispatch, payment, rating, complaint) -----

    @Test
    void bookingCreated_notifiesCustomerWithContactFromDirectoryAndReference() {
        UUID eventId = UUID.randomUUID();
        // booking-service BookingCreatedEvent
        consumer.onBookingCreated(record("BookingCreated", eventId, "{\"bookingId\":\"" + UUID.randomUUID()
                + "\",\"reference\":\"HFX-2026-0004821\",\"customerId\":\"" + CUSTOMER
                + "\",\"categoryId\":\"" + UUID.randomUUID() + "\",\"emergency\":false}"));

        NotificationEvent event = dispatched().get(0);
        assertThat(event.eventType()).isEqualTo(NotificationEventType.BOOKING_CREATED);
        assertThat(event.kafkaEventId()).isEqualTo(eventId);
        assertThat(event.recipientUserId()).isEqualTo(CUSTOMER);
        assertThat(event.audience()).isEqualTo(NotificationAudience.CUSTOMER);
        assertThat(event.contact()).isEqualTo(CUSTOMER_CONTACT);
        assertThat(event.attributes()).containsEntry("bookingReference", "HFX-2026-0004821");
        verify(processedEventRepository).save(any());
    }

    @Test
    void providerAccepted_fromDispatchEngine_notifiesCustomerOnly() {
        consumer.onProviderAccepted(record("ProviderAccepted", UUID.randomUUID(), "{\"bookingId\":\""
                + UUID.randomUUID() + "\",\"customerId\":\"" + CUSTOMER + "\",\"providerId\":\"" + PROVIDER
                + "\",\"acceptedAt\":\"2026-09-11T09:30:00Z\"}"));

        assertRecipients(CUSTOMER, NotificationAudience.CUSTOMER);
    }

    @Test
    void paymentCompleted_notifiesCustomerAndProvider() {
        consumer.onPaymentCompleted(record("PaymentCompleted", UUID.randomUUID(), "{\"paymentId\":\""
                + UUID.randomUUID() + "\",\"bookingId\":\"" + UUID.randomUUID() + "\",\"customerId\":\""
                + CUSTOMER + "\",\"providerId\":\"" + PROVIDER + "\",\"amount\":499.00}"));

        assertRecipients(CUSTOMER, NotificationAudience.CUSTOMER, PROVIDER, NotificationAudience.PROVIDER);
    }

    @Test
    void reviewSubmitted_notifiesReviewerAndReviewee() {
        consumer.onReviewSubmitted(record("ReviewSubmitted", UUID.randomUUID(), "{\"reviewId\":\""
                + UUID.randomUUID() + "\",\"bookingId\":\"" + UUID.randomUUID() + "\",\"reviewerId\":\""
                + CUSTOMER + "\",\"revieweeId\":\"" + PROVIDER + "\",\"reviewerRole\":\"CUSTOMER\","
                + "\"overallRating\":5}"));

        assertRecipients(CUSTOMER, NotificationAudience.REVIEWER, PROVIDER, NotificationAudience.REVIEWEE);
    }

    @Test
    void complaintCreated_acknowledgesCustomer() {
        consumer.onComplaintCreated(record("ComplaintCreated", UUID.randomUUID(), "{\"complaintId\":\""
                + UUID.randomUUID() + "\",\"bookingId\":\"" + UUID.randomUUID() + "\",\"customerId\":\""
                + CUSTOMER + "\",\"agentId\":\"" + UUID.randomUUID() + "\",\"category\":\"POOR_QUALITY\"}"));

        assertRecipients(CUSTOMER, NotificationAudience.CUSTOMER);
        assertThat(dispatched().get(0).eventType()).isEqualTo(NotificationEventType.COMPLAINT_CREATED);
    }

    @Test
    void complaintStatusChanged_notifiesCustomerWithNewStatus() {
        consumer.onComplaintStatusChanged(record("ComplaintStatusChanged", UUID.randomUUID(),
                "{\"complaintId\":\"" + UUID.randomUUID() + "\",\"bookingId\":\"" + UUID.randomUUID()
                        + "\",\"customerId\":\"" + CUSTOMER + "\",\"previousStatus\":\"OPEN\","
                        + "\"newStatus\":\"IN_PROGRESS\"}"));

        NotificationEvent event = dispatched().get(0);
        assertThat(event.eventType()).isEqualTo(NotificationEventType.COMPLAINT_STATUS_CHANGED);
        assertThat(event.recipientUserId()).isEqualTo(CUSTOMER);
        assertThat(event.attributes()).containsEntry("complaintStatus", "IN_PROGRESS");
    }

    // ----- payloads with fields being added by producers (customerId/providerId on booking events) -----

    @Test
    void bookingCancelled_withAssignedProvider_notifiesBoth_andWithoutProvider_notifiesCustomer() {
        consumer.onBookingCancelled(record("BookingCancelled", UUID.randomUUID(), "{\"bookingId\":\""
                + UUID.randomUUID() + "\",\"reference\":\"HFX-1\",\"customerId\":\"" + CUSTOMER
                + "\",\"providerId\":\"" + PROVIDER + "\"}"));
        assertRecipients(CUSTOMER, NotificationAudience.CUSTOMER, PROVIDER, NotificationAudience.PROVIDER);

        org.mockito.Mockito.clearInvocations(deliveryService);
        consumer.onBookingCancelled(record("BookingCancelled", UUID.randomUUID(), "{\"bookingId\":\""
                + UUID.randomUUID() + "\",\"reference\":\"HFX-2\",\"customerId\":\"" + CUSTOMER + "\"}"));
        assertRecipients(CUSTOMER, NotificationAudience.CUSTOMER);
    }

    @Test
    void providerAssigned_notifiesCustomerAndProvider() {
        consumer.onProviderAssigned(record("ProviderAssigned", UUID.randomUUID(), "{\"bookingId\":\""
                + UUID.randomUUID() + "\",\"customerId\":\"" + CUSTOMER + "\",\"providerId\":\"" + PROVIDER + "\"}"));

        assertRecipients(CUSTOMER, NotificationAudience.CUSTOMER, PROVIDER, NotificationAudience.PROVIDER);
    }

    @Test
    void everyCustomerFacingLifecycleListener_dispatchesItsEventType() {
        String withCustomer = "{\"bookingId\":\"" + UUID.randomUUID() + "\",\"customerId\":\"" + CUSTOMER
                + "\",\"providerId\":\"" + PROVIDER + "\"}";
        List<Consumer<ConsumerRecord<String, String>>> listeners = List.of(
                consumer::onProviderRejected, consumer::onProviderArriving, consumer::onProviderArrived,
                consumer::onJobStarted, consumer::onJobCompleted);
        List<String> topics = List.of("ProviderRejected", "ProviderArriving", "ProviderArrived",
                "JobStarted", "JobCompleted");
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).accept(record(topics.get(i), UUID.randomUUID(), withCustomer));
        }

        assertThat(dispatched())
                .extracting(NotificationEvent::eventType)
                .containsExactly(NotificationEventType.PROVIDER_REJECTED, NotificationEventType.PROVIDER_ARRIVING,
                        NotificationEventType.PROVIDER_ARRIVED, NotificationEventType.JOB_STARTED,
                        NotificationEventType.JOB_COMPLETED);
        assertThat(dispatched()).allSatisfy(e -> assertThat(e.recipientUserId()).isEqualTo(CUSTOMER));
    }

    @Test
    void recipientUnknownToDirectory_isStillAddressedWithEmptyContact() {
        consumer = consumerWith(new FakeContactDirectory());

        consumer.onBookingCreated(record("BookingCreated", UUID.randomUUID(),
                "{\"customerId\":\"" + CUSTOMER + "\"}"));

        NotificationEvent event = dispatched().get(0);
        assertThat(event.recipientUserId()).isEqualTo(CUSTOMER);
        assertThat(event.contact()).isEqualTo(NotificationContact.empty());
        verify(dlqForwarder, never()).forward(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ----- dedup and failure routing -----

    @Test
    void duplicateEventId_isDiscardedWithoutDispatch() {
        UUID eventId = UUID.randomUUID();
        when(processedEventRepository.existsByConsumerGroupAndEventId(anyString(), eq(eventId)))
                .thenReturn(true);

        consumer.onBookingCreated(record("BookingCreated", eventId, "{\"customerId\":\"" + CUSTOMER + "\"}"));

        verify(deliveryService, never()).dispatch(any());
        assertThat(directory.lookups.get()).isZero();
    }

    @Test
    void unknownTopic_isTreatedAsPoisonAndDeadLettered() {
        UUID eventId = UUID.randomUUID();

        // A record whose topic is not in the index cannot be mapped -> poison -> DLQ.
        consumer.onBookingCreated(record("UnknownTopic", eventId, "{}"));

        verify(deliveryService, never()).dispatch(any());
        verify(dlqForwarder).forward(eq("UnknownTopic"), eq("key"), eq(eventId.toString()), anyString(),
                anyString());
    }

    @Test
    void eventNamingNoRecipient_isDeadLetteredAsAProducerDefect() {
        UUID eventId = UUID.randomUUID();
        // ProviderArriving as booking-service publishes it before customerId is added: providerId only.
        consumer.onProviderArriving(record("ProviderArriving", eventId, "{\"bookingId\":\"" + UUID.randomUUID()
                + "\",\"reference\":\"HFX-1\",\"providerId\":\"" + PROVIDER + "\"}"));

        verify(deliveryService, never()).dispatch(any());
        verify(dlqForwarder).forward(eq("ProviderArriving"), eq("key"), eq(eventId.toString()), anyString(),
                contains("customerId"));
    }

    @Test
    void unreachableContactDirectory_isRetriedThenDeadLetteredWithNothingSent() {
        FakeContactDirectory down = FakeContactDirectory.failing();
        consumer = consumerWith(down);
        UUID eventId = UUID.randomUUID();

        consumer.onPaymentCompleted(record("PaymentCompleted", eventId, "{\"customerId\":\"" + CUSTOMER
                + "\",\"providerId\":\"" + PROVIDER + "\"}"));

        // The shared consumer's three attempts each consulted the directory; nothing was sent.
        assertThat(down.lookups.get()).isEqualTo(3);
        verify(deliveryService, never()).dispatch(any());
        verify(processedEventRepository, never()).save(any());
        verify(dlqForwarder).forward(eq("PaymentCompleted"), eq("key"), eq(eventId.toString()), anyString(),
                contains("ContactLookupException"));
    }
}
