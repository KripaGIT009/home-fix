package com.homefix.chat.consumer;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.chat.config.ChatProperties;
import com.homefix.chat.service.ChatService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Drives the chat channel lifecycle from booking-lifecycle Kafka events, idempotently
 * (Requirement 18.1, 18.5):
 * <ul>
 *   <li>{@code ProviderAccepted} -> activate the channel {@code (bookingId, customerId, providerId)}</li>
 *   <li>{@code PaymentCompleted} -> deactivate the channel</li>
 *   <li>{@code BookingCancelled} -> deactivate the channel</li>
 * </ul>
 *
 * <p>Consumer-level exactly-once handling (dedup on {@code (consumerGroup, eventId)}), retry, and
 * dead-letter forwarding are inherited from the shared {@link IdempotentKafkaConsumer} (Task 6).
 * The service-level operations are themselves idempotent, so any residual duplicate is harmless.
 */
@Component
public class ChannelLifecycleConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for the shared {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "chat-service.lifecycle";

    private static final Logger log = LoggerFactory.getLogger(ChannelLifecycleConsumer.class);

    private enum Action { ACTIVATE, DEACTIVATE }

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Action> topicToAction;

    public ChannelLifecycleConsumer(ProcessedEventRepository processedEventRepository,
                                    DlqForwarder dlqForwarder,
                                    ChatService chatService,
                                    ObjectMapper objectMapper,
                                    Clock clock,
                                    ChatProperties properties) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.topicToAction = buildTopicIndex(properties);
    }

    @KafkaListener(topics = "${homefix.chat.topics.provider-accepted:ProviderAccepted}",
            groupId = CONSUMER_GROUP)
    public void onProviderAccepted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.chat.topics.payment-completed:PaymentCompleted}",
            groupId = CONSUMER_GROUP)
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @KafkaListener(topics = "${homefix.chat.topics.booking-cancelled:BookingCancelled}",
            groupId = CONSUMER_GROUP)
    public void onBookingCancelled(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        Action action = topicToAction.get(record.topic());
        if (action == null) {
            // Should never happen: every listener binds a topic present in the index. Treat an
            // unknown topic as poison so it is dead-lettered rather than silently dropped.
            throw new IllegalStateException("No chat lifecycle action for topic " + record.topic());
        }
        LifecycleEventPayload payload = parse(record.value());
        if (payload.bookingId() == null) {
            throw new IllegalStateException("Lifecycle event payload is missing bookingId");
        }

        switch (action) {
            case ACTIVATE -> {
                if (payload.customerId() == null || payload.providerId() == null) {
                    throw new IllegalStateException(
                            "ProviderAccepted payload is missing customerId/providerId");
                }
                var createdAt = payload.bookingCreatedAt() != null
                        ? payload.bookingCreatedAt() : clock.instant();
                chatService.activateChannel(payload.bookingId(), payload.customerId(),
                        payload.providerId(), createdAt);
            }
            // The participant ids and creation time only matter if no channel exists yet, when
            // they go into the tombstone; either event may omit them.
            case DEACTIVATE -> chatService.deactivateChannel(payload.bookingId(),
                    payload.customerId(), payload.providerId(), payload.bookingCreatedAt());
        }
        log.debug("Applied {} for booking {} from topic {}",
                action, payload.bookingId(), record.topic());
    }

    private LifecycleEventPayload parse(String json) {
        try {
            return objectMapper.readValue(json, LifecycleEventPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable lifecycle event payload", e);
        }
    }

    private static Map<String, Action> buildTopicIndex(ChatProperties properties) {
        ChatProperties.Topics t = properties.getTopics();
        Map<String, Action> index = new HashMap<>();
        index.put(t.getProviderAccepted(), Action.ACTIVATE);
        index.put(t.getPaymentCompleted(), Action.DEACTIVATE);
        index.put(t.getBookingCancelled(), Action.DEACTIVATE);
        return Map.copyOf(index);
    }
}
