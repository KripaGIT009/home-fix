package com.homefix.provider.alert;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link AdminAlertPort} that logs a structured internal alert. A production adapter
 * can publish to Kafka via the shared outbox without touching the rating logic.
 */
@Component
public class LoggingAdminAlertAdapter implements AdminAlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdminAlertAdapter.class);

    @Override
    public void providerFlaggedForReview(UUID providerId, BigDecimal aggregateRating) {
        log.warn("ADMIN_ALERT provider={} flagged for review: aggregate rating {} dropped below threshold",
                providerId, aggregateRating);
    }
}
