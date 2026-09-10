package com.homefix.rating.provider;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link ProviderRatingPort} that logs the propagation and tracks UNDER_REVIEW status in
 * memory so repeated drops below threshold do not re-alert. A production adapter would call the
 * Provider Service (or publish an event) instead of holding local state.
 */
@Component
public class LoggingProviderRatingAdapter implements ProviderRatingPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProviderRatingAdapter.class);

    private final Set<UUID> underReview = ConcurrentHashMap.newKeySet();

    @Override
    public void updateAggregateRating(UUID providerId, BigDecimal aggregateRating) {
        log.debug("Propagating aggregate rating {} for provider {}", aggregateRating, providerId);
    }

    @Override
    public boolean flagUnderReview(UUID providerId) {
        boolean newlyFlagged = underReview.add(providerId);
        if (newlyFlagged) {
            log.warn("Provider {} flagged UNDER_REVIEW", providerId);
        }
        return newlyFlagged;
    }
}
