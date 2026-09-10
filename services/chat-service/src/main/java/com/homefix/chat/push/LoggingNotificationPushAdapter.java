package com.homefix.chat.push;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link NotificationPushPort} adapter used in local/dev/test profiles.
 *
 * <p>It does not contact the Notification Service — it records that a push would be raised. Neither
 * the message body nor any phone number is available to or logged by this adapter
 * (Requirement 26.4). Real deployments supply a concrete adapter that calls the Notification
 * Service, selected via {@code homefix.chat.push-provider}.
 *
 * <p>Activated when {@code homefix.chat.push-provider=log} (the default) or when no other
 * {@link NotificationPushPort} bean is present.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.chat", name = "push-provider",
        havingValue = "log", matchIfMissing = true)
public class LoggingNotificationPushAdapter implements NotificationPushPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationPushAdapter.class);

    @Override
    public void notifyUnreadMessage(UUID recipientId, UUID bookingId) {
        // Recipient and booking IDs are non-PII identifiers; message content is never referenced.
        log.info("Unread-message push accepted by logging adapter for booking {}", bookingId);
    }
}
