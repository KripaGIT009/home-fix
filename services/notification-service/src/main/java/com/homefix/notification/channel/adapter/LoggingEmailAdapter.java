package com.homefix.notification.channel.adapter;

import com.homefix.notification.channel.EmailPort;
import com.homefix.notification.channel.NotificationDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailPort} adapter used in local/dev/test profiles.
 *
 * <p>It does not contact an email provider — it records that a message would be sent. The
 * recipient address, subject, and body are never logged (Requirement 26.4). Real deployments
 * supply a concrete vendor adapter (SES/SendGrid) selected via
 * {@code homefix.notification.email-provider}.
 *
 * <p>Activated when {@code homefix.notification.email-provider=log} (the default) or when no
 * other {@link EmailPort} bean is present.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.notification", name = "email-provider",
        havingValue = "log", matchIfMissing = true)
public class LoggingEmailAdapter implements EmailPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailAdapter.class);

    @Override
    public void send(String emailAddress, String subject, String body)
            throws NotificationDeliveryException {
        // Do not log the recipient address (PII), subject, or body.
        log.info("Email dispatch accepted by logging adapter");
    }
}
