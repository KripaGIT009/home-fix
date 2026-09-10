package com.homefix.invoice.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefix.invoice.event.PaymentCompletedEvent;
import com.homefix.invoice.service.InvoiceService;
import com.homefix.shared.outbox.kafka.DlqForwarder;
import com.homefix.shared.outbox.kafka.IdempotentKafkaConsumer;
import com.homefix.shared.outbox.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PaymentCompleted} events idempotently and generates the corresponding invoice
 * (Requirements 13.1, 22.5). Deduplication, retry, and dead-lettering are inherited from the
 * shared {@link IdempotentKafkaConsumer} base; this class parses the payload and hands the event
 * to {@link InvoiceService}.
 *
 * <p>{@code InvoiceService} handles its own PDF-generation retry and, on exhaustion, alerts
 * operations and returns normally so a rendering fault never blocks or dead-letters the payment
 * flow (Requirement 13.7). Only genuinely unparseable payloads propagate to the DLQ path.
 */
@Component
public class PaymentCompletedConsumer extends IdempotentKafkaConsumer {

    /** Consumer group; also the dedup scope for {@code ProcessedEventEntity}. */
    public static final String CONSUMER_GROUP = "invoice-service.payment-completed";

    private static final Logger log = LoggerFactory.getLogger(PaymentCompletedConsumer.class);

    private final ObjectMapper objectMapper;
    private final InvoiceService invoiceService;

    public PaymentCompletedConsumer(ProcessedEventRepository processedEventRepository,
                                    DlqForwarder dlqForwarder,
                                    ObjectMapper objectMapper,
                                    InvoiceService invoiceService) {
        super(CONSUMER_GROUP, processedEventRepository, dlqForwarder);
        this.objectMapper = objectMapper;
        this.invoiceService = invoiceService;
    }

    @KafkaListener(
            topics = "${homefix.invoice.topics.payment-completed:PaymentCompleted}",
            groupId = CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        consume(record);
    }

    @Override
    protected void handle(ConsumerRecord<String, String> record) {
        PaymentCompletedEvent event = parse(record.value());
        log.debug("Generating invoice for payment {} (booking {})",
                event.paymentId(), event.bookingId());
        invoiceService.generateForPayment(event);
    }

    private PaymentCompletedEvent parse(String json) {
        try {
            return objectMapper.readValue(json, PaymentCompletedEvent.class);
        } catch (Exception e) {
            // Non-recoverable: a malformed payload will never parse. Throwing routes it to the DLQ
            // after the base class exhausts retries.
            throw new IllegalStateException("Unparseable PaymentCompleted payload", e);
        }
    }
}
