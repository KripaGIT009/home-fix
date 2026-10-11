package com.homefix.dispatch.port;

import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Shared storage for in-flight job offers (Requirements 8.5-8.7). The dispatch thread that made an
 * offer and the provider-facing API that records the provider's answer may run on different
 * Dispatch Engine instances, so the offers live in shared storage (Redis in production) rather
 * than in memory.
 *
 * <p>A booking's offers are stored together as one {@link OfferRound}, because a booking is
 * offered to several providers at once and accepting one must withdraw the others atomically. The
 * store knows nothing about the offer state machine: callers pass the transition (one of the pure
 * functions on {@link OfferRound}) to {@link #update}, and the store's only job is to apply it
 * atomically against concurrent writers.
 */
public interface JobOfferStore {

    /** The booking's offers, if any are stored and have not yet expired from storage. */
    Optional<OfferRound> find(UUID bookingId);

    /**
     * Offers indexed under {@code providerId}. May include offers that have since been decided or
     * closed; callers filter.
     */
    List<JobOffer> findByProvider(UUID providerId);

    /**
     * Atomically applies {@code transition} to the booking's round, starting from an empty round
     * when nothing is stored. Offers the transition adds are indexed under their providers. The
     * transition may be evaluated more than once if a concurrent writer gets in first; it must be a
     * pure function. Nothing is written when it returns the round unchanged.
     *
     * @param ttl when non-null, the round and any new index entries expire after it (opening a
     *            wave sets this); when null the existing expiry is kept
     * @return the round before and after the transition
     */
    Change update(UUID bookingId, UnaryOperator<OfferRound> transition, Duration ttl);

    /** A round as it was read and as it was left by {@link #update}. */
    record Change(OfferRound before, OfferRound after) {
    }
}
