package com.homefix.provider.verification;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Port to the Verification Service, which owns each provider's verification status
 * (Requirement 5). Dispatch eligibility requires {@code APPROVED} (Requirements 5.10, 8.2).
 */
public interface VerificationClientPort {

    /**
     * The subset of {@code providerIds} whose verification status is {@code APPROVED}.
     *
     * <p>Implementations must fail closed: when the Verification Service cannot answer, the
     * result is empty — an unverified provider must never be offered a job because a dependency
     * was down.
     */
    Set<UUID> approvedAmong(Collection<UUID> providerIds);
}
