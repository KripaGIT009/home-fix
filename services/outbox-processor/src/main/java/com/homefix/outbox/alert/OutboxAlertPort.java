package com.homefix.outbox.alert;

import java.util.UUID;

/**
 * Outbound port for alerting operations when an outbox event exhausts its publish retry budget
 * (Requirement 22.4). Hidden behind a port so the exhaustion path is unit-testable without a
 * real alerting channel; production can supply a PagerDuty/SNS adapter behind the same interface.
 */
public interface OutboxAlertPort {

    /**
     * Raises an alert for an event that could not be published after the maximum number of
     * attempts.
     *
     * @param eventId      the outbox event / Kafka {@code eventId} that failed to publish
     * @param topic        the Kafka topic the event was destined for
     * @param totalAttempts the total number of publish attempts made (initial attempt + retries)
     */
    void alertPublishExhausted(UUID eventId, String topic, int totalAttempts);
}
