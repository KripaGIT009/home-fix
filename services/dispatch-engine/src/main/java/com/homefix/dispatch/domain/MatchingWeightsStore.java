package com.homefix.dispatch.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently active dispatch {@link MatchingWeights} and applies Admin updates
 * transactionally with respect to validation (Requirements 8.4, 19.5; Property 19).
 *
 * <p>An update is applied if and only if the submitted weights are valid (each in [0.0, 1.0] and
 * summing to exactly 1.0). Validation happens during {@link MatchingWeights} construction, before
 * the atomic reference is touched, so a rejected update leaves the existing weights unchanged —
 * exactly as Requirement 19.5 demands. Reads are lock-free; the reference swap is atomic.
 */
@Component
public class MatchingWeightsStore {

    private static final Logger log = LoggerFactory.getLogger(MatchingWeightsStore.class);

    private final AtomicReference<MatchingWeights> current =
            new AtomicReference<>(MatchingWeights.DEFAULT);

    /** Returns the currently active weights (defaults until an Admin update succeeds). */
    public MatchingWeights current() {
        return current.get();
    }

    /**
     * Validates and, only if valid, applies {@code candidate} as the new active weights.
     *
     * @return the now-active weights
     * @throws WeightValidationException if the candidate is invalid; the active weights are
     *                                   left unchanged
     */
    public MatchingWeights update(MatchingWeights candidate) {
        // candidate is already validated by MatchingWeights construction; this method exists so the
        // swap and the "leave unchanged on failure" guarantee live in one obvious place.
        current.set(candidate);
        log.info("Dispatch matching weights updated: distance={}, availability={}, rating={}, skill={}, performance={}",
                candidate.distanceWeight(), candidate.availabilityWeight(), candidate.ratingWeight(),
                candidate.skillWeight(), candidate.performanceWeight());
        return candidate;
    }
}
