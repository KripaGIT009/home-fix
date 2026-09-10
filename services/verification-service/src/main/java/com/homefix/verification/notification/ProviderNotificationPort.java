package com.homefix.verification.notification;

import java.util.UUID;

/**
 * Abstraction over the Notification Service used to inform a provider of verification outcomes
 * (Requirement 5.8). Modelled as a port so the transport (Kafka via the outbox, direct REST
 * to the Notification Service, etc.) can vary without touching the verification workflow, and
 * so it is mockable in unit tests.
 */
public interface ProviderNotificationPort {

    /**
     * Notifies the provider that their verification application was rejected, including the
     * rejection reason (Requirement 5.8).
     */
    void notifyRejected(UUID providerId, String reason);
}
