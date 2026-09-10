package com.homefix.notification.channel;

/**
 * Hexagonal port abstracting the outbound email vendor (Requirement 17.3).
 *
 * <p>Notification business logic depends only on this interface, never on a concrete vendor SDK.
 * Swapping the email vendor (SES, SendGrid, ...) requires only a new adapter implementing this
 * port — no change to dispatch, deduplication, preference, or retry logic.
 */
public interface EmailPort {

    /**
     * Sends an email to the supplied address.
     *
     * @param emailAddress recipient email address
     * @param subject      the email subject line
     * @param body         the email body
     * @throws NotificationDeliveryException if the vendor rejects or fails to accept the message
     */
    void send(String emailAddress, String subject, String body) throws NotificationDeliveryException;
}
