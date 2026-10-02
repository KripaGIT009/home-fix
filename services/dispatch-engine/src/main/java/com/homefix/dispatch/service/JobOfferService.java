package com.homefix.dispatch.service;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The job-offer state machine as the rest of the Dispatch Engine uses it (Requirements 8.5-8.7):
 * the dispatch loop opens an offer and later closes it on timeout, the provider-facing API reads
 * and decides it, and booking cancellation withdraws it.
 *
 * <p>Every change goes through {@link JobOfferStore#update} with one of {@link JobOffer}'s pure
 * transitions, so the provider's decision and the dispatch timeout race on a single atomic
 * compare-and-set: whichever lands first wins and the other observes the result. The store entry
 * outlives the window by {@link #RETENTION_GRACE} so the dispatch thread can still read a decision
 * made in the last instant, and the provider can still see why a late accept was refused.
 *
 * <p>Registered by {@code DispatchAdaptersConfig} (it needs the Redis-backed store and a clock).
 */
public class JobOfferService {

    private static final Logger log = LoggerFactory.getLogger(JobOfferService.class);

    /** How long an offer stays readable after its window closes. */
    static final Duration RETENTION_GRACE = Duration.ofSeconds(30);

    private final JobOfferStore store;
    private final Clock clock;

    public JobOfferService(JobOfferStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Records a new PENDING offer of {@code request}'s booking to {@code providerId}. */
    public JobOffer open(DispatchRequest request, UUID providerId, Duration timeout) {
        return save(JobOffer.pending(request, providerId, clock.instant(), timeout), timeout);
    }

    /** Records a new PENDING offer that carries only the ids. */
    public JobOffer open(UUID bookingId, UUID providerId, Duration timeout) {
        return save(JobOffer.pending(bookingId, providerId, clock.instant(), timeout), timeout);
    }

    private JobOffer save(JobOffer offer, Duration timeout) {
        store.save(offer, timeout.plus(RETENTION_GRACE));
        return offer;
    }

    /** The current status of the booking's offer to {@code providerId}, if that offer is still stored. */
    public Optional<OfferStatus> statusOf(UUID bookingId, UUID providerId) {
        return store.find(bookingId).filter(o -> o.isOfferedTo(providerId)).map(JobOffer::status);
    }

    /**
     * Closes the offer on timeout. If the provider decided first, their decision stands and is
     * returned; otherwise the offer becomes EXPIRED, after which any accept is refused.
     *
     * @return the offer's final status (EXPIRED when it is no longer stored at all)
     */
    public OfferStatus expire(UUID bookingId, UUID providerId) {
        return store.update(bookingId, o -> o.close(providerId, OfferStatus.EXPIRED, clock.instant()))
                .map(JobOfferStore.Change::after)
                .filter(o -> o.isOfferedTo(providerId))
                .map(JobOffer::status)
                .orElse(OfferStatus.EXPIRED);
    }

    /** Withdraws a still-pending offer for a booking that no longer needs a provider. */
    public void withdraw(UUID bookingId) {
        store.update(bookingId, o -> o.close(null, OfferStatus.WITHDRAWN, clock.instant()))
                .filter(change -> change.after().status() == OfferStatus.WITHDRAWN
                        && change.before().status() == OfferStatus.PENDING)
                .ifPresent(change -> log.info("Withdrew pending offer of booking {} to provider {}",
                        bookingId, change.after().providerId()));
    }

    /** The caller's open offers, soonest-expiring first. */
    public List<JobOffer> pendingFor(UUID providerId) {
        var now = clock.instant();
        return store.findByProvider(providerId).stream()
                .filter(o -> o.isOfferedTo(providerId) && o.isOpenAt(now))
                .sorted(Comparator.comparing(JobOffer::expiresAt))
                .toList();
    }

    /** The booking's offer, but only if it was made to {@code providerId}. */
    public Optional<JobOffer> viewFor(UUID bookingId, UUID providerId) {
        return store.find(bookingId).filter(o -> o.isOfferedTo(providerId));
    }

    /**
     * Records the provider's accept or decline.
     *
     * @param decision {@link OfferStatus#ACCEPTED} or {@link OfferStatus#DECLINED}
     */
    public Decision decide(UUID bookingId, UUID providerId, OfferStatus decision) {
        Optional<JobOfferStore.Change> change =
                store.update(bookingId, o -> o.decide(providerId, decision, clock.instant()));
        if (change.isEmpty() || !change.get().before().isOfferedTo(providerId)) {
            // Absent and somebody else's look the same to the caller: no such offer for them.
            return new Decision(Decision.Outcome.NOT_FOUND, null);
        }
        JobOffer before = change.get().before();
        JobOffer after = change.get().after();
        if (before.status() != OfferStatus.PENDING) {
            return new Decision(before.status() == OfferStatus.EXPIRED
                    ? Decision.Outcome.EXPIRED : Decision.Outcome.ALREADY_DECIDED, before);
        }
        if (after.status() == OfferStatus.EXPIRED) {
            return new Decision(Decision.Outcome.EXPIRED, after);
        }
        log.info("Provider {} {} the offer for booking {}", providerId,
                decision == OfferStatus.ACCEPTED ? "accepted" : "declined", bookingId);
        return new Decision(Decision.Outcome.APPLIED, after);
    }

    /**
     * Result of a provider's decision.
     *
     * @param outcome what happened
     * @param offer   the offer as it now stands; {@code null} when {@code NOT_FOUND}
     */
    public record Decision(Outcome outcome, JobOffer offer) {

        public enum Outcome {
            /** The decision was recorded. */
            APPLIED,
            /** No offer for this booking was made to the caller (or it is no longer stored). */
            NOT_FOUND,
            /** The window had closed; the offer is EXPIRED and the decision was not recorded. */
            EXPIRED,
            /** The offer was already accepted, declined or withdrawn. */
            ALREADY_DECIDED
        }
    }
}
