package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.port.DistributedLockPort;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic in-memory {@link DistributedLockPort} for unit tests. Models the exclusive
 * acquire-if-absent semantics of the Redis lock without a broker: a provider can be held by at
 * most one handle at a time; a second acquire returns {@code null} until the first is released.
 */
public class InMemoryLock implements DistributedLockPort {

    private final Set<UUID> held = new HashSet<>();
    private int acquireAttempts;

    @Override
    public synchronized LockHandle tryAcquire(UUID providerId, Duration ttl) {
        acquireAttempts++;
        if (held.contains(providerId)) {
            return null;
        }
        held.add(providerId);
        return new LockHandle(providerId, UUID.randomUUID().toString(),
                "dispatch:lock:provider:" + providerId);
    }

    @Override
    public synchronized void release(LockHandle handle) {
        if (handle != null) {
            held.remove(handle.providerId());
        }
    }

    /** Test helper: pre-hold a provider's lock to simulate a concurrent booking flow. */
    public synchronized void forceHold(UUID providerId) {
        held.add(providerId);
    }

    /** Test helper: the concurrent flow that {@link #forceHold} simulated lets go. */
    public synchronized void forceRelease(UUID providerId) {
        held.remove(providerId);
    }

    public synchronized boolean isHeld(UUID providerId) {
        return held.contains(providerId);
    }

    public synchronized int acquireAttempts() {
        return acquireAttempts;
    }
}
