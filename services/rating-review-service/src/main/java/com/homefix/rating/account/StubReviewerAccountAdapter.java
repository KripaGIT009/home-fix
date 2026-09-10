package com.homefix.rating.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Default {@link ReviewerAccountPort} used until the Auth Service lookup is wired in. Returns empty,
 * which means fresh-account detection is skipped (a missing creation time cannot prove the account
 * is new). A production adapter would query the Auth Service.
 */
@Component
public class StubReviewerAccountAdapter implements ReviewerAccountPort {

    @Override
    public Optional<Instant> accountCreatedAt(UUID reviewerId) {
        return Optional.empty();
    }
}
