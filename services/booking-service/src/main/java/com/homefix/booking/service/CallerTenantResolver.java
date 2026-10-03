package com.homefix.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.homefix.booking.tenant.TenantDirectoryPort;
import com.homefix.booking.tenant.TenantDirectoryPort.TenantSummary;
import com.homefix.booking.tenant.TenantDirectoryUnavailableException;

/**
 * Resolves the Tenant a Tenant_Admin runs from the caller's identity, never from a request
 * parameter (Requirement MT-10.1, Property MT5), through the Provider Service's
 * {@code by-admin} lookup (design D2).
 *
 * <p>Answers are cached for {@value #TTL_SECONDS} s per user: the Tenant Portal polls its queue
 * every 15 s, and an admin's Tenant changes rarely. The cost is that a removal or suspension takes
 * up to a minute to reach booking-service, which the design accepts in place of a token claim.
 * "Not an admin of any Tenant" is cached too (so a misconfigured account does not hit the Provider
 * Service on every poll); an outage is not, so the next request asks again.
 */
@Component
public class CallerTenantResolver {

    static final long TTL_SECONDS = 60;
    private static final Duration TTL = Duration.ofSeconds(TTL_SECONDS);

    private final TenantDirectoryPort tenantDirectory;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    public CallerTenantResolver(TenantDirectoryPort tenantDirectory, Clock clock) {
        this.tenantDirectory = tenantDirectory;
        this.clock = clock;
    }

    /**
     * The Tenant {@code adminUserId} administers, if it may act.
     *
     * @throws BookingException 404 {@code TENANT_NOT_FOUND} when the caller administers no Tenant;
     *         403 {@code TENANT_SUSPENDED} when their Tenant is suspended (Requirement MT-1.4);
     *         503 {@code TENANT_DIRECTORY_UNAVAILABLE} when the Provider Service cannot be asked
     */
    public TenantSummary requireActiveTenant(UUID adminUserId) {
        TenantSummary tenant = lookup(adminUserId).orElseThrow(() -> new BookingException(
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "You do not administer a partner agency"));
        if (tenant.suspended()) {
            throw new BookingException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED",
                    "Your partner agency is suspended");
        }
        return tenant;
    }

    private Optional<TenantSummary> lookup(UUID adminUserId) {
        Instant now = Instant.now(clock);
        Entry cached = cache.get(adminUserId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.tenant();
        }
        Optional<TenantSummary> fresh;
        try {
            fresh = tenantDirectory.byAdmin(adminUserId);
        } catch (TenantDirectoryUnavailableException e) {
            throw unavailable();
        }
        cache.put(adminUserId, new Entry(fresh, now.plus(TTL)));
        return fresh;
    }

    static BookingException unavailable() {
        return new BookingException(HttpStatus.SERVICE_UNAVAILABLE, "TENANT_DIRECTORY_UNAVAILABLE",
                "Partner agency details are temporarily unavailable; please try again shortly");
    }

    private record Entry(Optional<TenantSummary> tenant, Instant expiresAt) {
    }
}
