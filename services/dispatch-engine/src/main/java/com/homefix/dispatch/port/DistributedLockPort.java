package com.homefix.dispatch.port;

import java.time.Duration;
import java.util.UUID;

/**
 * Outbound port for the exclusive per-provider offer lock (Requirement 8.11). Before a job offer
 * is sent, the Dispatch Engine must hold an exclusive lock on the provider's offer slot so that
 * no two concurrent booking flows can offer the same provider simultaneously.
 *
 * <p>The default adapter backs this with a Redis {@code SET key value NX PX ttl} on
 * {@code dispatch:lock:provider:{providerId}}, giving atomic acquire-if-absent semantics and an
 * automatic TTL expiry (default = the offer timeout, 60 s) so a crashed holder cannot deadlock a
 * provider forever. Ownership tokens ensure a holder only releases its own lock.
 */
public interface DistributedLockPort {

    /**
     * Attempts to acquire the exclusive offer lock for {@code providerId}.
     *
     * @param providerId the provider to lock
     * @param ttl        time-to-live after which the lock auto-expires
     * @return a lock handle if acquired, or {@code null} if another flow already holds it
     */
    LockHandle tryAcquire(UUID providerId, Duration ttl);

    /**
     * Releases a previously acquired lock. Releasing a lock whose token no longer matches (because
     * it expired and was re-acquired by someone else) is a no-op.
     */
    void release(LockHandle handle);

    /**
     * A handle to an acquired lock, carrying the provider it guards and the ownership token used to
     * make release safe against expiry races.
     */
    record LockHandle(UUID providerId, String token, String key) {
    }
}
