package com.homefix.customer.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically anonymizes customer PII for deletion requests whose 30-day window has
 * elapsed (Requirement 26.9). The heavy lifting lives in
 * {@link CustomerProfileService#anonymizeDueRequests()} so it can be unit-tested without a
 * scheduler.
 */
@Component
public class DeletionAnonymizationScheduler {

    private final CustomerProfileService service;

    public DeletionAnonymizationScheduler(CustomerProfileService service) {
        this.service = service;
    }

    /** Runs hourly; the exact cadence only needs to be well within the 30-day SLA. */
    @Scheduled(fixedDelayString = "${homefix.customer.deletion.sweep-interval:PT1H}")
    public void sweep() {
        service.anonymizeDueRequests();
    }
}
