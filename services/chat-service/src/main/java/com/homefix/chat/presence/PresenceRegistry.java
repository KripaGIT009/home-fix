package com.homefix.chat.presence;

import java.util.UUID;

/**
 * Tracks which users currently hold a live WebSocket connection, so the Chat Service can decide
 * whether a recipient is reachable in real time or must be reached via a push notification
 * (Requirement 18.3).
 *
 * <p>Modelled as a port so the presence source can evolve (in-memory for a single node, a shared
 * Redis registry across a horizontally-scaled fleet) without touching chat business logic.
 */
public interface PresenceRegistry {

    /** Records that {@code userId} has connected on the given booking's channel. */
    void markOnline(UUID bookingId, UUID userId);

    /** Records that {@code userId} has disconnected from the given booking's channel. */
    void markOffline(UUID bookingId, UUID userId);

    /**
     * @return {@code true} if {@code userId} currently has a live connection for {@code bookingId}
     *         and can therefore receive a message in real time.
     */
    boolean isOnline(UUID bookingId, UUID userId);
}
