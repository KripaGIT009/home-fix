package com.homefix.payment.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Fast store mapping an idempotency key to the transaction id it first produced (Requirement 12.3,
 * Property 11). Backed by Redis in production (design: "Stored in Redis with TTL sufficient to
 * cover the payment window") and by an in-memory map in tests.
 *
 * <p>The core idempotency guarantee is ultimately enforced by the unique {@code idempotency_key}
 * column on the transaction table; this store is the low-latency first line that lets duplicate
 * requests short-circuit without touching the gateway.
 */
public interface IdempotencyStorePort {

    /**
     * Atomically reserves {@code key} for {@code transactionId} if absent.
     *
     * @return {@code true} if this call created the entry (first attempt); {@code false} if an
     *         entry already existed (duplicate).
     */
    boolean putIfAbsent(String key, UUID transactionId, Duration ttl);

    /** @return the transaction id previously stored under {@code key}, if any. */
    Optional<UUID> find(String key);
}
