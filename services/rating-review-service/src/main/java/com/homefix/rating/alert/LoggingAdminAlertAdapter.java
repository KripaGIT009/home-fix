package com.homefix.rating.alert;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link AdminAlertPort} that logs a structured internal alert (Requirement 15.8). A
 * production adapter can publish to Kafka via the shared outbox without touching the rating logic.
 */
@Component
public class LoggingAdminAlertAdapter implements AdminAlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdminAlertAdapter.class);

    @Override
    public void providerBelowThreshold(UUID providerId, BigDecimal aggregateRating, BigDecimal threshold) {
        log.warn("ADMIN_ALERT provider={} aggregate={} dropped below threshold {}; flagged UNDER_REVIEW",
                providerId, aggregateRating, threshold);
    }
}
