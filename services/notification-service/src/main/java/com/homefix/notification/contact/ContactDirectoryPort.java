package com.homefix.notification.contact;

import java.util.Optional;
import java.util.UUID;

import com.homefix.notification.domain.NotificationContact;

/**
 * Resolves a platform user's delivery addresses from their user id.
 *
 * <p>Events carry user ids only; contact details are looked up at send time from the service that
 * owns them (the Auth Service, where every customer and provider registers). Keeping this behind a
 * port lets the consumer path be tested without HTTP and lets the source change without touching
 * delivery logic.
 */
public interface ContactDirectoryPort {

    /**
     * @return the user's contact details (individual addresses may be null), or empty when the
     *         directory has no such user — a permanent condition the caller should skip, not retry
     * @throws ContactLookupException when the directory could not be consulted (timeout, network,
     *                                5xx, rejected credentials) — a condition worth retrying
     */
    Optional<NotificationContact> findContact(UUID userId);
}
