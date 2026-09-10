package com.homefix.notification.channel.adapter;

import com.homefix.notification.channel.NotificationDeliveryException;
import com.homefix.notification.channel.SmsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link SmsPort} adapter used in local/dev/test profiles.
 *
 * <p>It does not contact a carrier — it records that a message would be sent. The recipient
 * number and message body are PII/sensitive and are therefore never logged (Requirement 26.4);
 * the shared PII scrubber (Task 5) provides defence-in-depth. Real deployments supply a concrete
 * vendor adapter (Twilio/Vonage/SNS) selected via {@code homefix.notification.sms-provider}.
 *
 * <p>Activated when {@code homefix.notification.sms-provider=log} (the default) or when no other
 * {@link SmsPort} bean is present.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.notification", name = "sms-provider",
        havingValue = "log", matchIfMissing = true)
public class LoggingSmsAdapter implements SmsPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsAdapter.class);

    @Override
    public void send(String mobileNumber, String message) throws NotificationDeliveryException {
        // Do not log the mobile number (PII) or the message body.
        log.info("SMS dispatch accepted by logging adapter");
    }
}
