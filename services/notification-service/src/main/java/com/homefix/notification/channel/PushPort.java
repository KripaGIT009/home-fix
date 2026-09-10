package com.homefix.notification.channel;

/**
 * Hexagonal port abstracting the outbound push-notification vendor (Requirement 17.1).
 *
 * <p>Notification business logic depends only on this interface, never on a concrete vendor SDK.
 * Swapping the push vendor (FCM, APNs, SNS, ...) requires only a new adapter implementing this
 * port.
 */
public interface PushPort {

    /**
     * Sends a push notification to the supplied device token.
     *
     * @param deviceToken opaque device/registration token for the recipient
     * @param title       the push notification title
     * @param body        the push notification body
     * @throws NotificationDeliveryException if the vendor rejects or fails to accept the message
     */
    void send(String deviceToken, String title, String body) throws NotificationDeliveryException;
}
