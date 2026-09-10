package com.homefix.rating.support;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.rating.provider.ProviderRatingPort;

/**
 * Recording {@link ProviderRatingPort} test double: captures the latest propagated aggregate per
 * provider and tracks UNDER_REVIEW status so the "flag once" behaviour can be asserted.
 */
public class RecordingProviderRatingPort implements ProviderRatingPort {

    private final Map<UUID, BigDecimal> aggregates = new HashMap<>();
    private final Set<UUID> underReview = ConcurrentHashMap.newKeySet();

    @Override
    public void updateAggregateRating(UUID providerId, BigDecimal aggregateRating) {
        aggregates.put(providerId, aggregateRating);
    }

    @Override
    public boolean flagUnderReview(UUID providerId) {
        return underReview.add(providerId);
    }

    public BigDecimal lastAggregate(UUID providerId) {
        return aggregates.get(providerId);
    }

    public boolean isUnderReview(UUID providerId) {
        return underReview.contains(providerId);
    }
}
