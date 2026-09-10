package com.homefix.payment.notification;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link ProviderNotificationPort} that logs the notification intent. A production adapter
 * would call the Notification Service (push + SMS) behind this same port.
 */
@Component
public class LoggingProviderNotificationAdapter implements ProviderNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingProviderNotificationAdapter.class);

    @Override
    public void settlementFailed(UUID providerId, UUID settlementId, BigDecimal amount) {
        log.info("PROVIDER_NOTIFY settlement_failed provider={} settlement={} amount={}",
                providerId, settlementId, amount);
    }
}
