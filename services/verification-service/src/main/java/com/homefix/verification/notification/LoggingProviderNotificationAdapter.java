package com.homefix.verification.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link ProviderNotificationPort} that logs the notification intent. A production
 * adapter can publish a notification event to Kafka via the shared outbox, or call the
 * Notification Service directly, without touching the verification workflow.
 */
@Component
public class LoggingProviderNotificationAdapter implements ProviderNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProviderNotificationAdapter.class);

    @Override
    public void notifyRejected(UUID providerId, String reason) {
        log.info("Notifying provider={} of verification rejection", providerId);
    }
}
