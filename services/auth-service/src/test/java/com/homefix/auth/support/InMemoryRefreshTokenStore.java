package com.homefix.auth.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.homefix.auth.token.RefreshTokenRecord;
import com.homefix.auth.token.RefreshTokenStore;

/**
 * Deterministic in-memory {@link RefreshTokenStore} for unit tests — no Redis required.
 *
 * <p>Tracks family membership so {@code revokeFamily} deletes every token in the family,
 * mirroring the Redis implementation's behaviour used for replay protection (Property 26).
 */
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, RefreshTokenRecord> tokens = new HashMap<>();
    private final Map<String, Set<String>> families = new HashMap<>();

    @Override
    public void save(String token, String subject, String familyId, Duration ttl) {
        tokens.put(token, RefreshTokenRecord.fresh(subject, familyId));
        families.computeIfAbsent(familyId, k -> new HashSet<>()).add(token);
    }

    @Override
    public Optional<RefreshTokenRecord> find(String token) {
        return Optional.ofNullable(tokens.get(token));
    }

    @Override
    public void markUsed(String token, Duration ttl) {
        RefreshTokenRecord current = tokens.get(token);
        if (current != null) {
            tokens.put(token, current.markUsed());
        }
    }

    @Override
    public void revoke(String token) {
        tokens.remove(token);
    }

    @Override
    public void revokeFamily(String familyId) {
        Set<String> members = families.remove(familyId);
        if (members != null) {
            members.forEach(tokens::remove);
        }
    }
}
