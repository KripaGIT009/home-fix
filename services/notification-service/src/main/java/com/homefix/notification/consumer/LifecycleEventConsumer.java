package com.homefix.notification.consumer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.homefix.notification.config.NotificationProperties;
import com.homefix.notification.contact.RecipientResolver;
import com.homefix.notification.delivery.NotificationDeliveryService;
import com.homefix.notification.domain.NotificationEvent;
import com.homefix.notification.domain.NotificationEventType;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes all 11 booking-lifecycle events and the two complaint events idempotently, addresses
 * each to its recipients, and hands each addressed notification to the
 * {@link NotificationDeliveryService} for multi-channel fan-out (Requirements 16.2, 16.3, 17.4,
 * 17.5).
 *
 * <p>Handling one record: parse it ({@link NotificationEventMapper}), decide the recipients and
 * resolve their contact details from the Auth Service ({@link RecipientResolver}), then dispatch
 * one notification per recipient. Failures route through the shared consumer's retry and
 * dead-letter path: an unreachable contact directory is retried and, if it stays down,
 * dead-lettered for replay; an event that names no recipient is dead-lettered as a producer
 * defect.
 *
 * <p>Consumer-level idempotency (dedup on {@code (consumerGroup, eventId)}) is inherited from the
 * shared {@link IdempotentKafkaConsumer}; the finer {@code (kafkaEventId, recipient, channel)}
 * dedup that prevents a duplicate on an individual channel lives in the delivery service
 * (Property 22). A single {@code @KafkaListener} per topic keeps each event type explicit; all
 * share one consumer group so the base dedup and DLQ semantics apply uniformly.
 */
@Component
public class LifecycleEventConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for the shared {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "notification-service.lifecycle";

    private static final Logger log = LoggerFactory.getLogger(LifecycleEventConsumer.class);

    private final NotificationEventMapper eventMapper;
    private final RecipientResolver recipientResolver;
    private final NotificationDeliveryService deliveryService;
    private final Map<String, NotificationEventType> topicToType;

    public LifecycleEventConsumer(ProcessedEventRepository processedEventRepository,
                                  DlqForwarder dlqForwarder,
                                  NotificationEventMapper eventMapper,
                                  RecipientResolver recipientResolver,
                                  NotificationDeliveryService deliveryService,
                                  NotificationProperties properties) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.eventMapper = eventMapper;
        this.recipientResolver = recipientResolver;
        this.deliveryService = deliveryService;
        this.topicToType = buildTopicIndex(properties);
    }

    // ----- one listener per lifecycle event (Requirement 17.5) -----

    @KafkaListener(topics = "${homefix.notification.topics.booking-created:BookingCreated}", groupId = CONSUMER_GROUP)
    public void onBookingCreated(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.provider-assigned:ProviderAssigned}", groupId = CONSUMER_GROUP)
    public void onProviderAssigned(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.provider-accepted:ProviderAccepted}", groupId = CONSUMER_GROUP)
    public void onProviderAccepted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.provider-rejected:ProviderRejected}", groupId = CONSUMER_GROUP)
    public void onProviderRejected(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.provider-arriving:ProviderArriving}", groupId = CONSUMER_GROUP)
    public void onProviderArriving(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.provider-arrived:ProviderArrived}", groupId = CONSUMER_GROUP)
    public void onProviderArrived(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.job-started:JobStarted}", groupId = CONSUMER_GROUP)
    public void onJobStarted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.job-completed:JobCompleted}", groupId = CONSUMER_GROUP)
    public void onJobCompleted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.payment-completed:PaymentCompleted}", groupId = CONSUMER_GROUP)
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.booking-cancelled:BookingCancelled}", groupId = CONSUMER_GROUP)
    public void onBookingCancelled(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.review-submitted:ReviewSubmitted}", groupId = CONSUMER_GROUP)
    public void onReviewSubmitted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    // ----- complaint events (Requirements 16.2, 16.3) -----

    @KafkaListener(topics = "${homefix.notification.topics.complaint-created:ComplaintCreated}", groupId = CONSUMER_GROUP)
    public void onComplaintCreated(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.notification.topics.complaint-status-changed:ComplaintStatusChanged}", groupId = CONSUMER_GROUP)
    public void onComplaintStatusChanged(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        NotificationEventType eventType = topicToType.get(record.topic());
        if (eventType == null) {
            // Should never happen: every listener binds a topic present in the index. Treating an
            // unknown topic as poison routes it to the DLQ rather than silently dropping it.
            throw new IllegalStateException("No notification mapping for topic " + record.topic());
        }
        log.debug("Dispatching notifications for {} event on topic {}", eventType, record.topic());
        InboundEvent inbound = eventMapper.map(eventType, record);
        // Resolve every recipient (and their contact) before sending anything, so a transient
        // directory failure retries the event with nothing yet delivered.
        List<NotificationEvent> addressed = recipientResolver.resolve(inbound);
        for (NotificationEvent event : addressed) {
            deliveryService.dispatch(event);
        }
    }

    private static Map<String, NotificationEventType> buildTopicIndex(NotificationProperties properties) {
        NotificationProperties.Topics t = properties.getTopics();
        Map<String, NotificationEventType> index = new HashMap<>();
        index.put(t.getBookingCreated(), NotificationEventType.BOOKING_CREATED);
        index.put(t.getProviderAssigned(), NotificationEventType.PROVIDER_ASSIGNED);
        index.put(t.getProviderAccepted(), NotificationEventType.PROVIDER_ACCEPTED);
        index.put(t.getProviderRejected(), NotificationEventType.PROVIDER_REJECTED);
        index.put(t.getProviderArriving(), NotificationEventType.PROVIDER_ARRIVING);
        index.put(t.getProviderArrived(), NotificationEventType.PROVIDER_ARRIVED);
        index.put(t.getJobStarted(), NotificationEventType.JOB_STARTED);
        index.put(t.getJobCompleted(), NotificationEventType.JOB_COMPLETED);
        index.put(t.getPaymentCompleted(), NotificationEventType.PAYMENT_COMPLETED);
        index.put(t.getBookingCancelled(), NotificationEventType.BOOKING_CANCELLED);
        index.put(t.getReviewSubmitted(), NotificationEventType.REVIEW_SUBMITTED);
        index.put(t.getComplaintCreated(), NotificationEventType.COMPLAINT_CREATED);
        index.put(t.getComplaintStatusChanged(), NotificationEventType.COMPLAINT_STATUS_CHANGED);
        return Map.copyOf(index);
    }
}
