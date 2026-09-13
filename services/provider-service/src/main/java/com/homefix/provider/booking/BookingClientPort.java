package com.homefix.provider.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Abstraction over the Booking Service's internal read surface, used to list the jobs a provider
 * currently has in flight (Requirement 28.8).
 *
 * <p>Cross-service data access is by API call only (no shared tables), so this port models the
 * remote lookup and keeps the dashboard assembly testable without a live Booking Service.
 */
public interface BookingClientPort {

    /**
     * @return the provider's active jobs, soonest scheduled first; empty when there are none, or
     *         when the Booking Service is unavailable and the call degrades.
     */
    List<ProviderJob> activeJobs(UUID providerId);

    /**
     * One in-flight job as the Booking Service reports it. {@code subcategoryId} is carried rather
     * than a service name because the name belongs to the Catalog Service.
     */
    record ProviderJob(
            UUID bookingId,
            String reference,
            UUID subcategoryId,
            String status,
            boolean emergency,
            Instant scheduledAt,
            BigDecimal estimatedTotal) {
    }
}
