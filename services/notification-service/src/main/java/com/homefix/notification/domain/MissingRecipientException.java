package com.homefix.notification.domain;

/**
 * An event does not name the user it must notify — a producer contract defect, not a transient
 * fault. It propagates out of the consumer so the event is dead-lettered with this message as the
 * reason and can be replayed once the producer is fixed. The message names only the event type and
 * the missing field, never any personal data.
 */
public class MissingRecipientException extends IllegalStateException {

    public MissingRecipientException(NotificationEventType eventType, String missingField) {
        super(eventType.eventName() + " event has no " + missingField + "; cannot address a recipient");
    }
}
