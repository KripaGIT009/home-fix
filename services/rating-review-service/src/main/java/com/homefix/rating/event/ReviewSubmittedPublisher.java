package com.homefix.rating.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homefix.rating.domain.Review;
import com.homefix.shared.outbox.OutboxEventPublisher;

/**
 * Publishes {@link ReviewSubmittedEvent} through the shared outbox (Requirement 15.6). Runs with
 * {@link Propagation#MANDATORY} so it always joins the caller's transaction, guaranteeing the
 * PENDING outbox row commits atomically with the review write; the shared Outbox Processor then
 * relays it to Kafka exactly-once.
 *
 * <p>Thin seam over {@link OutboxEventPublisher} so {@code ReviewService} can publish without
 * carrying serialization concerns, and so tests can substitute a fake publisher.
 */
@Component
public class ReviewSubmittedPublisher {

    private final OutboxEventPublisher outboxEventPublisher;

    public ReviewSubmittedPublisher(OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Review review) {
        ReviewSubmittedEvent event = new ReviewSubmittedEvent(
                review.getId(),
                review.getBookingId(),
                review.getReviewerId(),
                review.getRevieweeId(),
                review.getReviewerRole().name(),
                review.getOverallRating(),
                review.getSubmittedAt());
        outboxEventPublisher.publish(
                ReviewSubmittedEvent.AGGREGATE_TYPE,
                review.getId(),
                ReviewSubmittedEvent.EVENT_TYPE,
                event);
    }
}
