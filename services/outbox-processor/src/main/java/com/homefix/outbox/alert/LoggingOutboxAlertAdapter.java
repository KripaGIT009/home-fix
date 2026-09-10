package com.homefix.outbox.alert;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link OutboxAlertPort} adapter that logs a CRITICAL entry when an outbox event
 * exhausts its publish retries (Requirement 22.4). In production this can be replaced by a
 * PagerDuty/SNS adapter behind the same port without touching relay orchestration.
 *
 * <p>The alert carries only non-PII identifiers — event ID, topic, and attempt count — never the
 * event payload, honouring the no-PII-in-logs constraint (Requirement 26.4).
 *
 * <p>Active only when no other {@link OutboxAlertPort} bean is present (tests supply a fake).
 */
@Component
public class LoggingOutboxAlertAdapter implements OutboxAlertPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxAlertAdapter.class);

    @Override
    public void alertPublishExhausted(UUID eventId, String topic, int totalAttempts) {
        log.error("CRITICAL outbox event failed to publish after {} attempts; eventId={} topic={}",
                totalAttempts, eventId, topic);
    }
}
