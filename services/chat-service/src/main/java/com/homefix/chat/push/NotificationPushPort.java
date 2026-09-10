package com.homefix.chat.push;

import java.util.UUID;

/**
 * Hexagonal port abstracting the outbound "unread message" push notification raised via the
 * Notification Service when a recipient is offline (Requirement 18.3).
 *
 * <p>Chat business logic depends only on this interface, never on the Notification Service HTTP
 * client or a concrete push vendor. Swapping the transport (direct HTTP call, Kafka event, SNS,
 * ...) requires only a new adapter.
 */
public interface NotificationPushPort {

    /**
     * Notifies {@code recipientId} that they have an unread chat message on the given booking.
     * Implementations MUST NOT include the message body or any phone number (Requirement 26.4).
     *
     * @param recipientId the offline recipient
     * @param bookingId   the booking whose channel received the message
     */
    void notifyUnreadMessage(UUID recipientId, UUID bookingId);
}
