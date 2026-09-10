package com.homefix.payment.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link IdempotencyStorePort} for dev/test (no Redis required). TTL is ignored; the
 * atomicity of {@code putIfAbsent} is provided by {@link ConcurrentHashMap#putIfAbsent}.
 */
public class InMemoryIdempotencyStoreAdapter implements IdempotencyStorePort {

    private final ConcurrentHashMap<String, UUID> store = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String key, UUID transactionId, Duration ttl) {
        return store.putIfAbsent(key, transactionId) == null;
    }

    @Override
    public Optional<UUID> find(String key) {
        return Optional.ofNullable(store.get(key));
    }
}
