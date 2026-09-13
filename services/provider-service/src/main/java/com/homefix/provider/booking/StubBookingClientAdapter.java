package com.homefix.provider.booking;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link BookingClientPort} used when no Booking Service is configured — it reports that the
 * provider has no active jobs.
 *
 * <p>Returning an empty list rather than throwing keeps the dashboard renderable in environments
 * that run the Provider Service on its own; the active-job panel simply shows nothing.
 */
@Component
@ConditionalOnProperty(name = "homefix.booking.client", havingValue = "stub", matchIfMissing = true)
public class StubBookingClientAdapter implements BookingClientPort {

    private static final Logger log = LoggerFactory.getLogger(StubBookingClientAdapter.class);

    @Override
    public List<ProviderJob> activeJobs(UUID providerId) {
        log.debug("Stub booking client: reporting no active jobs for provider {}", providerId);
        return List.of();
    }
}
