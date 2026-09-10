package com.homefix.payment.notification;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Abstraction over the Notification Service used to inform a provider of settlement outcomes via
 * push notification and SMS (Requirement 14.4). Modelled as a port so the transport can vary and
 * so it is mockable in unit tests.
 */
public interface ProviderNotificationPort {

    /** Notifies the provider that a settlement bank transfer failed (Requirement 14.4). */
    void settlementFailed(UUID providerId, UUID settlementId, BigDecimal amount);
}
