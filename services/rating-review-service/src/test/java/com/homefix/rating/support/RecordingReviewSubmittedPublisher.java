package com.homefix.rating.support;

import java.util.ArrayList;
import java.util.List;

import com.homefix.rating.domain.Review;
import com.homefix.rating.event.ReviewSubmittedPublisher;

/**
 * Test double for {@link ReviewSubmittedPublisher} that records published reviews instead of
 * writing an outbox row, so {@code ReviewService} can be tested without a database or an active
 * transaction. Overrides {@code publish} and never invokes the outbox-backed super logic.
 */
public class RecordingReviewSubmittedPublisher extends ReviewSubmittedPublisher {

    private final List<Review> published = new ArrayList<>();

    public RecordingReviewSubmittedPublisher() {
        // No outbox publisher needed: publish() is fully overridden below.
        super(null);
    }

    @Override
    public void publish(Review review) {
        published.add(review);
    }

    public List<Review> published() {
        return published;
    }
}
