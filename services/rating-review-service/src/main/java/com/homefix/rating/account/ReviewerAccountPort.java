package com.homefix.rating.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves when a reviewer's account was created, so fresh-account fraud detection can run
 * (Requirement 15.5c). Modelled as a port so the source (Auth Service, cache, etc.) can vary and so
 * it is mockable in unit tests.
 */
public interface ReviewerAccountPort {

    /** @return the reviewer account creation instant, or empty if unknown. */
    Optional<Instant> accountCreatedAt(UUID reviewerId);
}
