package com.homefix.notification.channel;

import java.util.UUID;

/**
 * Hexagonal port abstracting in-app notification delivery (Requirement 17.1).
 *
 * <p>In-app notifications are persisted for the user to read inside the app; this port hides the
 * concrete store/transport from the dispatch logic.
 */
public interface InAppPort {

    /**
     * Publishes an in-app notification for the given user.
     *
     * @param userId  the recipient user
     * @param title   the notification title
     * @param body    the notification body
     * @throws NotificationDeliveryException if the notification cannot be published
     */
    void publish(UUID userId, String title, String body) throws NotificationDeliveryException;
}
