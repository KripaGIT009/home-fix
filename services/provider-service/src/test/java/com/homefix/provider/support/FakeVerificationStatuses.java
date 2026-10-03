package com.homefix.provider.support;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.homefix.provider.verification.VerificationAdminClientPort;

/**
 * Scripted {@link VerificationAdminClientPort} for Tenant team tests: raw verification statuses by
 * provider id, and a switch for "the Verification Service cannot answer". The write half is not
 * used by the Tenant services.
 */
public class FakeVerificationStatuses implements VerificationAdminClientPort {

    public final Map<UUID, String> statuses = new HashMap<>();
    public boolean available = true;
    public int lookups;

    @Override
    public Optional<Map<UUID, String>> statusesOf(Collection<UUID> providerIds) {
        lookups++;
        if (!available) {
            return Optional.empty();
        }
        Map<UUID, String> found = new HashMap<>(statuses);
        found.keySet().retainAll(providerIds);
        return Optional.of(found);
    }

    @Override
    public String suspend(UUID providerId, UUID actorId, String reason) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String reinstate(UUID providerId, UUID actorId, String reason) {
        throw new UnsupportedOperationException();
    }
}
