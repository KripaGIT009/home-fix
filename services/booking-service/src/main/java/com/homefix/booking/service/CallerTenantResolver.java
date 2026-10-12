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
 * <p>Every answer is an authorization decision, and the Provider Service refuses a removed admin at
 * once, so booking-service keeps the window in which a removed or suspended admin is still served
 * as small as the latency budget allows:
 * <ul>
 *   <li><b>Reads</b> ({@link #requireActiveTenant}: the queue and the Tenant's bookings) reuse an
 *       answer for at most {@value #TTL_SECONDS} s. That absorbs the burst of parallel calls one
 *       Tenant Portal page load makes, while the portal's 15 s poll always asks afresh. It was
 *       60 s, which let a removed admin keep reading the queue for up to a minute.</li>
 *   <li><b>Changes</b> ({@link #requireActiveTenantNow}: assigning a booking) always ask the Provider
 *       Service and refresh the cached answer. An assignment already makes a membership call, so one
 *       more lookup is a small, rare cost, and a removed admin can never assign.</li>
 * </ul>
 * "Not an admin of any Tenant" is cached too (so a misconfigured account does not hit the Provider
 * Service on every call); an outage is not, so the next request asks again.
 */
@Component
public class CallerTenantResolver {

    static final long TTL_SECONDS = 5;
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
        return requireActive(lookup(adminUserId, false));
    }

    /**
     * As {@link #requireActiveTenant}, but always asking the Provider Service rather than reusing a
     * recent answer: for actions that change a booking, where a just-removed admin must be refused.
     *
     * @throws BookingException as {@link #requireActiveTenant}
     */
    public TenantSummary requireActiveTenantNow(UUID adminUserId) {
        return requireActive(lookup(adminUserId, true));
    }

    private static TenantSummary requireActive(Optional<TenantSummary> found) {
        TenantSummary tenant = found.orElseThrow(() -> new BookingException(
                HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "You do not administer a partner agency"));
        if (tenant.suspended()) {
            throw new BookingException(HttpStatus.FORBIDDEN, "TENANT_SUSPENDED",
                    "Your partner agency is suspended");
        }
        return tenant;
    }

    private Optional<TenantSummary> lookup(UUID adminUserId, boolean bypassCache) {
        Instant now = Instant.now(clock);
        Entry cached = bypassCache ? null : cache.get(adminUserId);
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
