package com.homefix.location.subscription;

import java.io.IOException;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link SubscriberSession} backed by a Spring MVC {@link SseEmitter}. Each accepted location
 * update is delivered as an {@code SSE} event named {@code location}; a send failure surfaces
 * as a {@link RuntimeException} so the registry drops the dead session (Requirement 10.2).
 */
public class SseSubscriberSession implements SubscriberSession {

    private final String sessionId;
    private final SseEmitter emitter;

    public SseSubscriberSession(String sessionId, SseEmitter emitter) {
        this.sessionId = sessionId;
        this.emitter = emitter;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void send(LocationUpdatePush update) {
        try {
            emitter.send(SseEmitter.event().name("location").data(update));
        } catch (IOException | IllegalStateException e) {
            // Client gone or emitter already completed; signal the registry to drop us.
            throw new SubscriberSendException("Failed to send SSE event to session " + sessionId, e);
        }
    }

    @Override
    public void close() {
        emitter.complete();
    }

    /** Unchecked wrapper so the registry can uniformly drop failing sessions. */
    public static class SubscriberSendException extends RuntimeException {
        public SubscriberSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
