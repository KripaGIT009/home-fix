package com.homefix.notification.channel.adapter;

import java.util.UUID;

import com.homefix.notification.channel.InAppPort;
import com.homefix.notification.channel.NotificationDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link InAppPort} adapter used in local/dev/test profiles.
 *
 * <p>It records that an in-app notification would be published without contacting a real store.
 * Message content is never logged (Requirement 26.4). Activated when no other {@link InAppPort}
 * bean is present.
 */
@Component
public class LoggingInAppAdapter implements InAppPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingInAppAdapter.class);

    @Override
    public void publish(UUID userId, String title, String body)
            throws NotificationDeliveryException {
        // Do not log message content; the userId is a non-PII opaque identifier.
        log.info("In-app notification accepted by logging adapter for user {}", userId);
    }
}
