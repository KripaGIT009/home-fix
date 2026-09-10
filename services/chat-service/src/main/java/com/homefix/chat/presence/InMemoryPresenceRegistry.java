package com.homefix.chat.presence;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Default single-node {@link PresenceRegistry} backed by an in-memory set of connected
 * {@code (bookingId, userId)} pairs.
 *
 * <p>Adequate for local/dev and a single chat-service instance. A multi-node deployment supplies a
 * shared implementation (e.g. Redis-backed) which, being another {@link PresenceRegistry} bean,
 * transparently replaces this one via {@link ConditionalOnMissingBean}.
 */
@Component
public class InMemoryPresenceRegistry implements PresenceRegistry {

    private final Set<String> onlinePairs = ConcurrentHashMap.newKeySet();

    @Override
    public void markOnline(UUID bookingId, UUID userId) {
        onlinePairs.add(key(bookingId, userId));
    }

    @Override
    public void markOffline(UUID bookingId, UUID userId) {
        onlinePairs.remove(key(bookingId, userId));
    }

    @Override
    public boolean isOnline(UUID bookingId, UUID userId) {
        return onlinePairs.contains(key(bookingId, userId));
    }

    private static String key(UUID bookingId, UUID userId) {
        return bookingId + "|" + userId;
    }
}
