package com.homefix.notification.channel;

/**
 * Hexagonal port abstracting the outbound SMS vendor (Requirement 17.2).
 *
 * <p>Notification business logic depends only on this interface, never on a concrete vendor SDK.
 * Swapping the SMS vendor (Twilio, Vonage, SNS, ...) requires only a new adapter implementing
 * this port — no change to dispatch, deduplication, preference, or retry logic.
 */
public interface SmsPort {

    /**
     * Sends an SMS message to the supplied mobile number.
     *
     * @param mobileNumber E.164 mobile number (e.g. {@code +919876543210})
     * @param message      the message body to deliver
     * @throws NotificationDeliveryException if the vendor rejects or fails to accept the message
     */
    void send(String mobileNumber, String message) throws NotificationDeliveryException;
}
