package com.homefix.dispatch.port;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.ProviderCandidate;

import java.util.List;

/**
 * Outbound port to the Provider Service for finding eligible providers for a booking
 * (Requirement 8.2). Per the design's per-service database isolation rule the Dispatch Engine
 * never reads provider tables directly; it queries the Provider Service, which applies the
 * eligibility filters (APPROVED verification status, at least one matching skill tag, emergency
 * availability when required) and returns pre-computed {@code ScoreComponents}.
 *
 * <p>Abstracting this behind a port keeps the dispatch orchestration testable with a
 * deterministic fake and lets the transport (HTTP today) change without touching the algorithm.
 */
public interface ProviderQueryPort {

    /**
     * Finds eligible providers for {@code request} within {@code radiusKm} of the customer.
     *
     * @param request  the dispatch problem (location, skills, emergency flag)
     * @param radiusKm the current search radius in kilometres
     * @return eligible candidates with their component scores (never {@code null}; may be empty).
     *         Empty means the Provider Service answered and nobody qualified
     * @throws com.homefix.dispatch.domain.ProviderSearchUnavailableException when the Provider
     *         Service could not be asked (outage, open breaker, refused credential); an
     *         implementation must never report that as an empty list
     */
    List<ProviderCandidate> findEligibleProviders(DispatchRequest request, double radiusKm);
}
