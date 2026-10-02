package com.homefix.notification.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One addressee of an event: the platform user id and the part they play in it. Contact details
 * are resolved from the user id at send time, never carried on the event.
 *
 * @param userId   the platform user (auth-service account id)
 * @param audience which message variant the user receives
 */
public record Recipient(UUID userId, NotificationAudience audience) {

    public Recipient {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(audience, "audience");
    }
}
