package com.homefix.dispatch.service;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The job-offer state machine as the rest of the Dispatch Engine uses it (Requirements 8.5-8.7):
 * the dispatch loop opens a wave of offers and later closes it on timeout, the provider-facing API
 * reads and decides them, and booking cancellation withdraws them.
 *
 * <p>A booking's offers form one {@link OfferRound}, and every change goes through
 * {@link JobOfferStore#update} with one of its pure transitions. Providers' decisions and the
 * wave's timeout therefore race on a single atomic compare-and-set: the first accept wins, takes
 * the booking and withdraws everyone else's offer in the same write, and any later accept is
 * refused as {@link Decision.Outcome#TAKEN}. The store entry outlives the window by
 * {@link #RETENTION_GRACE} so the dispatch thread can still read a decision made in the last
 * instant, and providers can still see why a late accept was refused.
 *
 * <p>Registered by {@code DispatchAdaptersConfig} (it needs the Redis-backed store and a clock).
 */
public class JobOfferService {

    private static final Logger log = LoggerFactory.getLogger(JobOfferService.class);

    /** How long offers stay readable after their window closes. */
    static final Duration RETENTION_GRACE = Duration.ofSeconds(30);

    private final JobOfferStore store;
    private final Clock clock;

    public JobOfferService(JobOfferStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Opens a wave: records a PENDING offer of {@code request}'s booking to every provider in
     * {@code providerIds}, all with the same window. Nothing is opened once the booking has been
     * accepted.
     *
     * @return the offers opened, in {@code providerIds} order; empty when the booking was already taken
     */
    public List<JobOffer> open(DispatchRequest request, List<UUID> providerIds, Duration timeout) {
        Instant now = clock.instant();
        List<JobOffer> wave = providerIds.stream()
                .map(p -> JobOffer.pending(request, p, now, timeout))
                .toList();
        OfferRound after = store.update(request.bookingId(), round -> round.open(wave),
                timeout.plus(RETENTION_GRACE)).after();
        return wave.stream()
                .filter(o -> after.offerTo(o.providerId()).filter(o::equals).isPresent())
                .toList();
    }

    /** All of the booking's offers, if any are still stored. */
    public Optional<OfferRound> round(UUID bookingId) {
        return store.find(bookingId);
    }

    /** The current status of the booking's offer to {@code providerId}, if that offer is still stored. */
    public Optional<OfferStatus> statusOf(UUID bookingId, UUID providerId) {
        return store.find(bookingId).flatMap(r -> r.offerTo(providerId)).map(JobOffer::status);
    }

    /**
     * Closes a wave on timeout. Offers the providers decided first keep their decision; the rest
     * become EXPIRED, after which any accept is refused.
     *
     * @return the round as it now stands (empty when it is no longer stored at all)
     */
    public OfferRound expire(UUID bookingId, Collection<UUID> providerIds) {
        return store.update(bookingId, r -> r.close(providerIds, OfferStatus.EXPIRED, clock.instant()), null)
                .after();
    }

    /** Withdraws every still-pending offer for a booking that no longer needs a provider. */
    public void withdraw(UUID bookingId) {
        JobOfferStore.Change change =
                store.update(bookingId, r -> r.close(null, OfferStatus.WITHDRAWN, clock.instant()), null);
        for (JobOffer before : change.before().offers()) {
            if (before.status() == OfferStatus.PENDING
                    && change.after().offerTo(before.providerId())
                    .filter(o -> o.status() == OfferStatus.WITHDRAWN).isPresent()) {
                log.info("Withdrew pending offer of booking {} to provider {}", bookingId, before.providerId());
            }
        }
    }

    /** The caller's open offers, soonest-expiring first. */
    public List<JobOffer> pendingFor(UUID providerId) {
        Instant now = clock.instant();
        return store.findByProvider(providerId).stream()
                .filter(o -> o.isOfferedTo(providerId) && o.isOpenAt(now))
                .sorted(Comparator.comparing(JobOffer::expiresAt))
                .toList();
    }

    /** The booking's offer, but only if one was made to {@code providerId}. */
    public Optional<JobOffer> viewFor(UUID bookingId, UUID providerId) {
        return store.find(bookingId).flatMap(r -> r.offerTo(providerId));
    }

    /**
     * Records the provider's accept or decline. An accept that lands first takes the booking and
     * withdraws every other provider's offer in the same atomic write.
     *
     * @param decision {@link OfferStatus#ACCEPTED} or {@link OfferStatus#DECLINED}
     */
    public Decision decide(UUID bookingId, UUID providerId, OfferStatus decision) {
        Instant now = clock.instant();
        JobOfferStore.Change change = store.update(bookingId, r -> r.decide(providerId, decision, now), null);
        Optional<JobOffer> before = change.before().offerTo(providerId);
        if (before.isEmpty()) {
            // Absent and offered to somebody else look the same to the caller: no such offer for them.
            return new Decision(Decision.Outcome.NOT_FOUND, null);
        }
        JobOffer mine = before.get();
        if (mine.status() != OfferStatus.PENDING) {
            if (mine.status() == OfferStatus.EXPIRED) {
                return new Decision(Decision.Outcome.EXPIRED, mine);
            }
            boolean takenByAnother = mine.status() == OfferStatus.WITHDRAWN
                    && change.before().accepted().filter(o -> !o.isOfferedTo(providerId)).isPresent();
            return new Decision(takenByAnother ? Decision.Outcome.TAKEN : Decision.Outcome.ALREADY_DECIDED, mine);
        }
        JobOffer after = change.after().offerTo(providerId).orElse(mine);
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
     * @param offer   the caller's offer as it now stands; {@code null} when {@code NOT_FOUND}
     */
    public record Decision(Outcome outcome, JobOffer offer) {

        public enum Outcome {
            /** The decision was recorded. */
            APPLIED,
            /** No offer for this booking was made to the caller (or it is no longer stored). */
            NOT_FOUND,
            /** The window had closed; the offer is EXPIRED and the decision was not recorded. */
            EXPIRED,
            /** Another provider offered the same booking accepted it first. */
            TAKEN,
            /** The caller's offer was already accepted, declined or withdrawn. */
            ALREADY_DECIDED
        }
    }
}
