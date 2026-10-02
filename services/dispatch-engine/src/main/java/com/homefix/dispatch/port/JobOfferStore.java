package com.homefix.dispatch.port;

import com.homefix.dispatch.domain.JobOffer;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Shared storage for in-flight job offers (Requirements 8.5-8.7). The dispatch thread that made an
 * offer and the provider-facing API that records the provider's answer may run on different
 * Dispatch Engine instances, so the offer lives in shared storage (Redis in production) rather
 * than in memory.
 *
 * <p>The store knows nothing about the offer state machine: callers pass the transition (one of
 * the pure functions on {@link JobOffer}) to {@link #update}, and the store's only job is to apply
 * it atomically against concurrent writers.
 */
public interface JobOfferStore {

    /**
     * Records {@code offer} for its booking, replacing any earlier offer for that booking, and
     * indexes it under its provider. Both entries expire after {@code ttl}.
     */
    void save(JobOffer offer, Duration ttl);

    /** The current offer for a booking, if one is stored and has not yet expired from storage. */
    Optional<JobOffer> find(UUID bookingId);

    /**
     * Offers indexed under {@code providerId}. May include offers that have since been decided,
     * closed, or replaced by an offer to someone else; callers filter.
     */
    List<JobOffer> findByProvider(UUID providerId);

    /**
     * Atomically applies {@code transition} to the booking's stored offer. The transition may be
     * evaluated more than once if a concurrent writer gets in first; it must be a pure function.
     *
     * @return the offer before and after the transition, or empty when no offer is stored
     */
    Optional<Change> update(UUID bookingId, UnaryOperator<JobOffer> transition);

    /** An offer as it was read and as it was left by {@link #update}. */
    record Change(JobOffer before, JobOffer after) {
    }
}
