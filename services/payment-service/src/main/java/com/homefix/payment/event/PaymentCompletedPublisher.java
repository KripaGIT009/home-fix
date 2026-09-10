package com.homefix.payment.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.payment.domain.PaymentTransaction;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Publishes {@link PaymentCompletedEvent} through the shared outbox (Requirement 12.6). Runs with
 * {@link Propagation#MANDATORY} so it always joins the caller's transaction, guaranteeing the
 * PENDING outbox row commits atomically with the transaction's move to SUCCESS; the shared Outbox
 * Processor then relays it to Kafka exactly-once.
 *
 * <p>Thin seam over {@link OutboxEventPublisher} so {@code PaymentService} can publish without
 * carrying serialization concerns, and so tests can substitute a fake publisher.
 */
@Component
public class PaymentCompletedPublisher {

    private final OutboxEventPublisher outboxEventPublisher;

    public PaymentCompletedPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(PaymentTransaction tx) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                tx.getId(),
                tx.getBookingId(),
                tx.getCustomerId(),
                tx.getProviderId(),
                tx.getAmount(),
                tx.getPlatformFee(),
                tx.providerNetEarning(),
                tx.getMethod().name(),
                tx.getUpdatedAt());
        outboxEventPublisher.publish(
                PaymentCompletedEvent.AGGREGATE_TYPE,
                tx.getId(),
                PaymentCompletedEvent.EVENT_TYPE,
                event);
    }
}
