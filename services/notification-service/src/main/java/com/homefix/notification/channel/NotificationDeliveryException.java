package com.homefix.notification.channel;

/**
 * Thrown by a channel port adapter (SMS, email, push, in-app) when a notification cannot be
 * delivered.
 *
 * <p>A delivery failure is treated as recoverable: the orchestrator retries up to the configured
 * limit with exponential backoff before marking the delivery permanently failed
 * (Requirement 17.8). The exception message is stored in the delivery log's {@code
 * error_description} field but must never contain PII (Requirement 26.4).
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
