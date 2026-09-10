package com.homefix.location.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.homefix.location.domain.Coordinates;

/**
 * Unit tests for {@link InMemorySubscriberRegistry}: register/deregister lifecycle, fan-out,
 * and termination of all sessions on JOB_STARTED (Requirement 10.5).
 */
class InMemorySubscriberRegistryTest {

    private final InMemorySubscriberRegistry registry = new InMemorySubscriberRegistry();
    private final UUID bookingId = UUID.randomUUID();

    private static LocationUpdatePush anyPush() {
        return new LocationUpdatePush(new Coordinates(1.0, 2.0), 5);
    }

    @Test
    void registerThenPushDeliversToAllSessions() {
        RecordingSession a = new RecordingSession("a");
        RecordingSession b = new RecordingSession("b");
        registry.register(bookingId, a);
        registry.register(bookingId, b);

        registry.push(bookingId, anyPush());

        assertThat(registry.subscriberCount(bookingId)).isEqualTo(2);
        assertThat(a.received).hasSize(1);
        assertThat(b.received).hasSize(1);
    }

    @Test
    void deregisterStopsDelivery() {
        RecordingSession a = new RecordingSession("a");
        registry.register(bookingId, a);
        registry.deregister(bookingId, a);

        registry.push(bookingId, anyPush());

        assertThat(registry.subscriberCount(bookingId)).isZero();
        assertThat(a.received).isEmpty();
    }

    @Test
    void terminateAllClosesEverySessionAndRemovesThem() {
        RecordingSession a = new RecordingSession("a");
        RecordingSession b = new RecordingSession("b");
        registry.register(bookingId, a);
        registry.register(bookingId, b);

        registry.terminateAll(bookingId);

        assertThat(registry.subscriberCount(bookingId)).isZero();
        assertThat(a.closed).isTrue();
        assertThat(b.closed).isTrue();
        // No further deliveries after termination.
        registry.push(bookingId, anyPush());
        assertThat(a.received).isEmpty();
    }

    @Test
    void failingSessionIsDroppedWithoutBlockingOthers() {
        RecordingSession good = new RecordingSession("good");
        SubscriberSession bad = new SubscriberSession() {
            @Override public String sessionId() { return "bad"; }
            @Override public void send(LocationUpdatePush update) {
                throw new RuntimeException("client gone");
            }
            @Override public void close() { }
        };
        registry.register(bookingId, bad);
        registry.register(bookingId, good);

        registry.push(bookingId, anyPush());

        assertThat(good.received).hasSize(1);
        // The failing session was dropped.
        assertThat(registry.subscriberCount(bookingId)).isEqualTo(1);
    }

    /** Test double recording delivered pushes and close calls. */
    private static final class RecordingSession implements SubscriberSession {
        private final String id;
        final List<LocationUpdatePush> received = new ArrayList<>();
        boolean closed = false;

        RecordingSession(String id) {
            this.id = id;
        }

        @Override public String sessionId() { return id; }
        @Override public void send(LocationUpdatePush update) { received.add(update); }
        @Override public void close() { closed = true; }
    }
}
