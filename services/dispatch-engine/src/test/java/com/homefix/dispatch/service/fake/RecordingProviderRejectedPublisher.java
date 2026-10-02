package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.service.ProviderRejectedPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test double for {@link ProviderRejectedPublisher} that records each published rejection instead
 * of writing an outbox row, so {@code DispatchService} can be tested without a database
 * (Requirement 8.7). Can be told to fail, to prove a failed publish does not abort the search.
 */
public class RecordingProviderRejectedPublisher extends ProviderRejectedPublisher {

    /** One recorded {@code ProviderRejected} publication. */
    public record Rejection(UUID bookingId, UUID customerId, UUID providerId, String reason) {
    }

    private final List<Rejection> rejections = new ArrayList<>();
    private boolean failing;

    public RecordingProviderRejectedPublisher() {
        // No outbox publisher needed: publish() is fully overridden below.
        super(null);
    }

    /** Makes every subsequent publish throw, as an unavailable outbox database would. */
    public RecordingProviderRejectedPublisher failing() {
        this.failing = true;
        return this;
    }

    @Override
    public void publish(UUID bookingId, UUID customerId, UUID providerId, String reason) {
        if (failing) {
            throw new IllegalStateException("outbox unavailable");
        }
        rejections.add(new Rejection(bookingId, customerId, providerId, reason));
    }

    /** Rejections published, in order. */
    public List<Rejection> rejections() {
        return rejections;
    }
}
