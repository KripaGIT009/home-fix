package com.homefix.location.subscription;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory, thread-safe {@link SubscriberRegistryPort} that fans updates out to the
 * WebSocket/SSE sessions registered for each Booking (Requirement 10.2). A per-Booking
 * concurrent map of session id → session gives O(1) register/deregister and safe iteration
 * during fan-out.
 *
 * <p>This registry is process-local. In a multi-instance deployment the ingestion instance and
 * the instance holding a Customer's WebSocket may differ; production wiring would bridge pushes
 * across instances (e.g. via Redis pub/sub). The lifecycle contract and termination semantics
 * (Requirement 10.5) are identical regardless of that bridge.
 */
public class InMemorySubscriberRegistry implements SubscriberRegistryPort {

    private static final Logger log = LoggerFactory.getLogger(InMemorySubscriberRegistry.class);

    private final Map<UUID, Map<String, SubscriberSession>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void register(UUID bookingId, SubscriberSession session) {
        subscribers.computeIfAbsent(bookingId, id -> new ConcurrentHashMap<>())
                .put(session.sessionId(), session);
        log.debug("Registered subscriber {} for booking {}", session.sessionId(), bookingId);
    }

    @Override
    public void deregister(UUID bookingId, SubscriberSession session) {
        Map<String, SubscriberSession> sessions = subscribers.get(bookingId);
        if (sessions != null) {
            sessions.remove(session.sessionId());
            if (sessions.isEmpty()) {
                subscribers.remove(bookingId, sessions);
            }
        }
    }

    @Override
    public void push(UUID bookingId, LocationUpdatePush update) {
        Map<String, SubscriberSession> sessions = subscribers.get(bookingId);
        if (sessions == null) {
            return;
        }
        for (SubscriberSession session : sessions.values()) {
            try {
                session.send(update);
            } catch (RuntimeException ex) {
                // A failing session must not block delivery to the others; drop it.
                log.warn("Dropping subscriber {} for booking {} after send failure: {}",
                        session.sessionId(), bookingId, ex.toString());
                deregister(bookingId, session);
            }
        }
    }

    @Override
    public void terminateAll(UUID bookingId) {
        Map<String, SubscriberSession> sessions = subscribers.remove(bookingId);
        if (sessions == null) {
            return;
        }
        for (SubscriberSession session : sessions.values()) {
            try {
                session.close();
            } catch (RuntimeException ex) {
                log.warn("Error closing subscriber {} for booking {}: {}",
                        session.sessionId(), bookingId, ex.toString());
            }
        }
        log.debug("Terminated {} subscriber(s) for booking {}", sessions.size(), bookingId);
    }

    @Override
    public int subscriberCount(UUID bookingId) {
        Map<String, SubscriberSession> sessions = subscribers.get(bookingId);
        return sessions == null ? 0 : sessions.size();
    }
}
