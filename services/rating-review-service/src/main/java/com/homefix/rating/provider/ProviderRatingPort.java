package com.homefix.rating.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Propagates a recomputed aggregate rating and any resulting UNDER_REVIEW flag to the Provider
 * Service (Requirement 15.4, 15.7, 15.8). Modelled as a port so the transport (synchronous HTTP,
 * Kafka event, etc.) can vary and so it is mockable in unit tests.
 */
public interface ProviderRatingPort {

    /** Persists the provider's newly-recalculated aggregate rating (Requirement 15.4). */
    void updateAggregateRating(UUID providerId, BigDecimal aggregateRating);

    /**
     * Flags the provider account UNDER_REVIEW when the aggregate drops below threshold and the
     * provider is not already under review (Requirement 15.7, 15.8).
     *
     * @return {@code true} if this call transitioned the provider into UNDER_REVIEW, {@code false}
     *         if it was already under review (so the caller can avoid a redundant alert)
     */
    boolean flagUnderReview(UUID providerId);
}
