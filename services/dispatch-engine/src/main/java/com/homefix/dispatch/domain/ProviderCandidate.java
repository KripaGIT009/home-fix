package com.homefix.dispatch.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * An eligible provider returned by the {@code ProviderQueryPort} for a booking, already carrying
 * its five normalised {@link ScoreComponents}. The Dispatch Engine turns each candidate into a
 * ranked offer target using the active {@link MatchingWeights}.
 *
 * <p>The provider-service (the source of truth) is responsible for filtering to APPROVED status,
 * matching skill tags, emergency availability, and being inside the current radius; it also
 * supplies the component scores. The Dispatch Engine owns only the weighting and ranking.
 */
public record ProviderCandidate(
        UUID providerId,
        ScoreComponents components) {

    public ProviderCandidate {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(components, "components must not be null");
    }

    /** The matching score of this candidate under the supplied weights (Requirement 8.3). */
    public double score(MatchingWeights weights) {
        return weights.score(components);
    }
}
