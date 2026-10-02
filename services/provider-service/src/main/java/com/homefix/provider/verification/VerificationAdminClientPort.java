package com.homefix.provider.verification;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port to the Verification Service for the Admin provider list (Requirement 19.2): reading each
 * provider's verification status, and suspending / reinstating a provider on an Admin's behalf.
 *
 * <p>Kept apart from {@link VerificationClientPort} on purpose. That port serves the dispatch
 * eligibility search and must fail <em>closed</em>; these reads serve an Admin screen and fail
 * <em>soft</em>, and the writes must surface every failure. Mixing them would invite one policy to
 * leak into the other. The same adapter implements both, so they share one client, credential and
 * circuit breaker.
 */
public interface VerificationAdminClientPort {

    /**
     * The raw verification status ({@code VerificationStatus} name) of each of {@code providerIds}
     * that has a verification record; ids with none are absent from the map.
     *
     * @return the statuses, or {@link Optional#empty()} when the Verification Service could not be
     *         asked — callers then show the status as unknown rather than failing.
     */
    Optional<Map<UUID, String>> statusesOf(Collection<UUID> providerIds);

    /**
     * Suspends an {@code APPROVED} provider (Requirement 5.9), recording {@code actorId} as the
     * acting Admin.
     *
     * @return the provider's verification status afterwards ({@code SUSPENDED})
     * @throws com.homefix.provider.service.ProviderException carrying the Verification Service's
     *         4xx (e.g. 409 {@code INVALID_STATE_TRANSITION}), or 503
     *         {@code VERIFICATION_UNAVAILABLE} when it cannot be reached
     */
    String suspend(UUID providerId, UUID actorId, String reason);

    /**
     * Reinstates a {@code SUSPENDED} provider to {@code APPROVED} (Requirement 5.1). Never performs
     * a first-time approval.
     *
     * @return the provider's verification status afterwards ({@code APPROVED})
     * @throws com.homefix.provider.service.ProviderException as for {@link #suspend}
     */
    String reinstate(UUID providerId, UUID actorId, String reason);
}
