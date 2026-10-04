package com.homefix.dispatch.service.fake;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.domain.ProviderSearchUnavailableException;
import com.homefix.dispatch.port.ProviderQueryPort;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Fake {@link ProviderQueryPort} that returns providers according to the radius asked for, so a
 * test can control exactly which candidates appear in each expansion cycle. Candidates registered
 * at radius R are returned for any query with {@code radiusKm >= R} (mirroring "within radius").
 * {@link #unavailableFor(int)} makes queries fail as the HTTP adapter does when the Provider Service
 * cannot be asked.
 */
public class ScriptedProviderQuery implements ProviderQueryPort {

    private final NavigableMap<Double, List<ProviderCandidate>> byMinRadius = new TreeMap<>();
    private final List<Double> queriedRadii = new ArrayList<>();
    private int unavailableQueries;

    /** Registers candidates that become eligible once the search radius reaches {@code minRadiusKm}. */
    public ScriptedProviderQuery whenRadiusAtLeast(double minRadiusKm, ProviderCandidate... candidates) {
        byMinRadius.computeIfAbsent(minRadiusKm, k -> new ArrayList<>()).addAll(List.of(candidates));
        return this;
    }

    /** Makes the next {@code queries} queries throw; {@link Integer#MAX_VALUE} for a lasting outage. */
    public ScriptedProviderQuery unavailableFor(int queries) {
        this.unavailableQueries = queries;
        return this;
    }

    @Override
    public List<ProviderCandidate> findEligibleProviders(DispatchRequest request, double radiusKm) {
        queriedRadii.add(radiusKm);
        if (unavailableQueries > 0) {
            unavailableQueries--;
            throw new ProviderSearchUnavailableException("Provider Service returned HTTP 401", null);
        }
        List<ProviderCandidate> result = new ArrayList<>();
        byMinRadius.headMap(radiusKm, true).values().forEach(result::addAll);
        return result;
    }

    /** The radii, in order, that the dispatch loop queried — lets a test assert the cycle count. */
    public List<Double> queriedRadii() {
        return queriedRadii;
    }
}
