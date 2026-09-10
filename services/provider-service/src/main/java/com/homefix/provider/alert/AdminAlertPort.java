package com.homefix.provider.alert;

import java.util.UUID;

/**
 * Emits internal alerts to the Admin team (Requirement 4.7). Modelled as a port so the
 * alerting transport (Kafka via the outbox, notification service, etc.) can vary without
 * touching the rating business logic, and so it is mockable in unit tests.
 */
public interface AdminAlertPort {

    /**
     * Alerts Admin that a provider has been flagged for review after their aggregate rating
     * dropped below the configured threshold.
     */
    void providerFlaggedForReview(UUID providerId, java.math.BigDecimal aggregateRating);
}
