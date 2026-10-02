package com.homefix.verification.provider;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port to the Provider Service, which owns each provider's profile (display name, skills). The
 * Verification Service owns none of that; the Admin review queue (Requirement 19.3) borrows it
 * only so the reviewer can see <em>who</em> is waiting.
 */
public interface ProviderDirectoryPort {

    /**
     * Display name and primary skill for each of {@code providerIds} that has a profile.
     *
     * <p>Implementations must fail soft: when the Provider Service cannot answer, the result is
     * empty and the queue is shown without names — a cosmetic lookup must never take the review
     * queue down with it.
     */
    Map<UUID, ProviderSummary> summariesOf(Collection<UUID> providerIds);

    /** What the review queue shows about a provider; either field may be {@code null}. */
    record ProviderSummary(String displayName, String primarySkill) {
    }
}
