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
 * <p>Tracks family membership so {@code revokeFamily} deletes every token in the family, and
 * remembers revoked families so a later {@code save} into one is refused, mirroring the Redis
 * implementation's behaviour used for replay protection (Property 26). Every method is
 * synchronized, which gives {@link #consume} the same all-or-nothing semantics the Redis Lua
 * script has, so concurrency tests exercise the real contract. Families are also indexed by
 * subject, as in Redis, so {@code revokeAllForSubject} ends every session of one account.
 */
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

    private final Map<String, RefreshTokenRecord> tokens = new HashMap<>();
    private final Map<String, Set<String>> families = new HashMap<>();
    private final Set<String> revokedFamilies = new HashSet<>();
    private final Map<String, Set<String>> familiesBySubject = new HashMap<>();

    @Override
    public synchronized boolean save(String token, String subject, String familyId, Duration ttl) {
        if (revokedFamilies.contains(familyId)) {
            return false;
        }
        tokens.put(token, RefreshTokenRecord.fresh(subject, familyId));
        families.computeIfAbsent(familyId, k -> new HashSet<>()).add(token);
        familiesBySubject.computeIfAbsent(subject, k -> new HashSet<>()).add(familyId);
        return true;
    }

    @Override
    public synchronized Optional<RefreshTokenRecord> find(String token) {
        return Optional.ofNullable(tokens.get(token));
    }

    @Override
    public synchronized Optional<RefreshTokenRecord> consume(String token, Duration ttl) {
        RefreshTokenRecord current = tokens.get(token);
        if (current != null && !current.used()) {
            tokens.put(token, current.markUsed());
        }
        return Optional.ofNullable(current);
    }

    @Override
    public synchronized void revoke(String token) {
        tokens.remove(token);
    }

    @Override
    public synchronized void revokeFamily(String familyId) {
        revokedFamilies.add(familyId);
        Set<String> members = families.remove(familyId);
        if (members != null) {
            members.forEach(tokens::remove);
        }
    }

    @Override
    public synchronized void revokeAllForSubject(String subject) {
        Set<String> subjectFamilies = familiesBySubject.remove(subject);
        if (subjectFamilies != null) {
            subjectFamilies.forEach(this::revokeFamily);
        }
    }
}
