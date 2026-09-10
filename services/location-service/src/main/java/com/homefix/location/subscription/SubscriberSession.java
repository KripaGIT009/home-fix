package com.homefix.location.subscription;

/**
 * Transport-agnostic handle for a single Customer subscription to a Booking's location feed.
 * A WebSocket implementation wraps a {@code WebSocketSession}; an SSE implementation wraps an
 * {@code SseEmitter}. The registry uses these operations to deliver pushes and to close the
 * session when the feed terminates (Requirement 10.5).
 */
public interface SubscriberSession {

    /**
     * A stable identifier for the underlying transport session, used for deduplication within
     * the registry.
     */
    String sessionId();

    /**
     * Delivers a location update to this subscriber.
     */
    void send(LocationUpdatePush update);

    /**
     * Closes the underlying transport session. Called when the Booking's feed is terminated.
     */
    void close();
}
