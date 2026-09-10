package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.service.ProviderAcceptedPublisher;

import java.util.UUID;

/**
 * Test double for {@link ProviderAcceptedPublisher} that records the published event instead of
 * writing an outbox row, so {@code DispatchService} can be tested without a database
 * (Requirement 8.6). Overrides {@code publish} and never invokes the outbox-backed super logic.
 */
public class RecordingProviderAcceptedPublisher extends ProviderAcceptedPublisher {

    private UUID publishedBookingId;
    private UUID publishedProviderId;
    private int publishCount;

    public RecordingProviderAcceptedPublisher() {
        // No outbox publisher needed: publish() is fully overridden below.
        super(null);
    }

    @Override
    public void publish(UUID bookingId, UUID providerId) {
        this.publishedBookingId = bookingId;
        this.publishedProviderId = providerId;
        this.publishCount++;
    }

    public boolean published() {
        return publishCount > 0;
    }

    public int publishCount() {
        return publishCount;
    }

    public UUID publishedProviderId() {
        return publishedProviderId;
    }

    public UUID publishedBookingId() {
        return publishedBookingId;
    }
}
