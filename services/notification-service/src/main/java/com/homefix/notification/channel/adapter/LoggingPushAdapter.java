package com.homefix.notification.channel.adapter;

import com.homefix.notification.channel.NotificationDeliveryException;
import com.homefix.notification.channel.PushPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link PushPort} adapter used in local/dev/test profiles.
 *
 * <p>It does not contact a push provider — it records that a message would be sent. The device
 * token, title, and body are never logged (Requirement 26.4). Real deployments supply a concrete
 * vendor adapter (FCM/APNs/SNS) selected via {@code homefix.notification.push-provider}.
 *
 * <p>Activated when {@code homefix.notification.push-provider=log} (the default) or when no other
 * {@link PushPort} bean is present.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.notification", name = "push-provider",
        havingValue = "log", matchIfMissing = true)
public class LoggingPushAdapter implements PushPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushAdapter.class);

    @Override
    public void send(String deviceToken, String title, String body)
            throws NotificationDeliveryException {
        // Do not log the device token or message content.
        log.info("Push dispatch accepted by logging adapter");
    }
}
