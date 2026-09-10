package com.homefix.rating.alert;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emits internal alerts to the Admin team (Requirement 15.8). Modelled as a port so the alerting
 * transport (Kafka via the outbox, notification service, etc.) can vary without touching the rating
 * business logic, and so it is mockable in unit tests.
 */
public interface AdminAlertPort {

    /**
     * Alerts Admin that a provider's aggregate rating fell below the configured threshold and the
     * account has been flagged UNDER_REVIEW.
     */
    void providerBelowThreshold(UUID providerId, BigDecimal aggregateRating, BigDecimal threshold);
}
