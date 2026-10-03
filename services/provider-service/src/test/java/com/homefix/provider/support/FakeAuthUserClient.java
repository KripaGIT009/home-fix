package com.homefix.provider.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.homefix.provider.auth.AuthUserClientPort;
import com.homefix.provider.service.ProviderException;

/**
 * Scripted {@link AuthUserClientPort} for Tenant service tests: accounts registered by mobile
 * number, a log of grant / revoke calls, failures to inject, and a hook that runs inside a grant so
 * a test can stage a concurrent change between the grant and the membership insert.
 */
public class FakeAuthUserClient implements AuthUserClientPort {

    private final Map<String, AuthUser> byMobile = new HashMap<>();
    private final Map<UUID, String> mobileOf = new HashMap<>();

    /** "grant &lt;userId&gt;" / "revoke &lt;userId&gt;", in call order. */
    public final List<String> calls = new CopyOnWriteArrayList<>();

    public ProviderException lookupFailure;
    public ProviderException grantFailure;
    public ProviderException revokeFailure;
    public Consumer<UUID> onGrant = id -> { };

    /** Registers an account and returns its user id. */
    public UUID register(String mobileNumber, String... roles) {
        UUID id = UUID.randomUUID();
        register(id, mobileNumber, roles);
        return id;
    }

    public void register(UUID userId, String mobileNumber, String... roles) {
        byMobile.put(mobileNumber, new AuthUser(userId, Set.of(roles), "ACTIVE"));
        mobileOf.put(userId, mobileNumber);
    }

    @Override
    public Optional<AuthUser> findByMobile(String mobileNumber) {
        if (lookupFailure != null) {
            throw lookupFailure;
        }
        return Optional.ofNullable(byMobile.get(mobileNumber));
    }

    @Override
    public void grantTenantAdmin(UUID userId) {
        if (grantFailure != null) {
            throw grantFailure;
        }
        calls.add("grant " + userId);
        onGrant.accept(userId);
    }

    @Override
    public void revokeTenantAdmin(UUID userId) {
        if (revokeFailure != null) {
            throw revokeFailure;
        }
        calls.add("revoke " + userId);
    }

    @Override
    public Optional<String> mobileNumberOf(UUID userId) {
        return Optional.ofNullable(mobileOf.get(userId));
    }

    public List<String> callsSnapshot() {
        return new ArrayList<>(calls);
    }
}
