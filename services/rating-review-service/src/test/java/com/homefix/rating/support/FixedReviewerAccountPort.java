package com.homefix.rating.support;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.homefix.rating.account.ReviewerAccountPort;

/**
 * Test double for {@link ReviewerAccountPort} with per-reviewer creation instants configured up
 * front. Unknown reviewers return empty (creation time unknown), matching the production stub.
 */
public class FixedReviewerAccountPort implements ReviewerAccountPort {

    private final Map<UUID, Instant> createdAt = new HashMap<>();

    public FixedReviewerAccountPort set(UUID reviewerId, Instant createdAt) {
        this.createdAt.put(reviewerId, createdAt);
        return this;
    }

    @Override
    public Optional<Instant> accountCreatedAt(UUID reviewerId) {
        return Optional.ofNullable(createdAt.get(reviewerId));
    }
}
