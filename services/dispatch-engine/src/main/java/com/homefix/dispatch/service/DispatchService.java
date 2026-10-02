package com.homefix.dispatch.service;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.BookingNotSearchableException;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.event.ProviderRejectedEvent;
import com.homefix.dispatch.port.BookingCancellationPort;
import com.homefix.dispatch.port.BookingTransitionPort;
import com.homefix.dispatch.port.DistributedLockPort;
import com.homefix.dispatch.port.JobOfferPort;
import com.homefix.dispatch.port.NotificationPort;
import com.homefix.dispatch.port.ProviderQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Core dispatch orchestration (Requirements 8.2-8.11). Given a {@link DispatchRequest} it:
 *
 * <ol>
 *   <li>queries eligible providers within the current radius (Requirement 8.2);</li>
 *   <li>scores and ranks them using the active {@link MatchingWeights} (Requirement 8.3);</li>
 *   <li>walks the ranking highest-first, acquiring an exclusive per-provider Redis lock before
 *       each offer (Requirement 8.11) and offering the job (Requirement 8.5);</li>
 *   <li>when a provider is busy — their lock is held by another booking's open offer — goes on to
 *       the next candidate, then waits for the busy ones (at most one offer window) before giving
 *       up on the radius. A busy provider has not seen this job, so they are not dropped from the
 *       pool the way a decliner is;</li>
 *   <li>on acceptance, transitions the booking to PROVIDER_ACCEPTED and publishes ProviderAccepted
 *       (Requirement 8.6);</li>
 *   <li>on reject/timeout, publishes ProviderRejected, drops that provider from the pool and moves
 *       to the next (Requirement 8.7);</li>
 *   <li>when a radius is exhausted with no acceptance, expands by the configured increment up to
 *       the cycle limit (Requirement 8.8);</li>
 *   <li>if all cycles are exhausted, transitions the booking to SEARCHING_FAILED and fires the
 *       customer + dispatcher notifications (Requirement 8.9).</li>
 * </ol>
 *
 * <p>The search stops early, quietly, once the booking no longer needs a provider: the
 * {@code BookingCancelled} flag is checked before every offer and again before a declined or
 * unanswered offer is announced, and a 409/404 from the Booking Service on either terminal
 * transition ({@link BookingNotSearchableException}) ends the run the same way. Without this a
 * cancelled booking kept being offered to, and rejected by, every remaining candidate.
 *
 * <p>All external effects are behind ports so the whole algorithm is exercised with deterministic
 * fakes in unit tests. The class is stateless and thread-safe: it is invoked concurrently from the
 * emergency and scheduled bulkhead thread pools.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    /** Pause between attempts on busy providers; abstracted so tests can drive time. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final ProviderQueryPort providerQuery;
    private final DistributedLockPort lock;
    private final JobOfferPort jobOffer;
    private final BookingTransitionPort bookingTransition;
    private final NotificationPort notification;
    private final MatchingWeightsStore weightsStore;
    private final DispatchProperties properties;
    private final ProviderAcceptedPublisher acceptedPublisher;
    private final ProviderRejectedPublisher rejectedPublisher;
    private final BookingCancellationPort cancellation;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public DispatchService(ProviderQueryPort providerQuery,
                           DistributedLockPort lock,
                           JobOfferPort jobOffer,
                           BookingTransitionPort bookingTransition,
                           NotificationPort notification,
                           MatchingWeightsStore weightsStore,
                           DispatchProperties properties,
                           ProviderAcceptedPublisher acceptedPublisher,
                           ProviderRejectedPublisher rejectedPublisher,
                           BookingCancellationPort cancellation,
                           Clock clock) {
        this(providerQuery, lock, jobOffer, bookingTransition, notification, weightsStore, properties,
                acceptedPublisher, rejectedPublisher, cancellation, clock, Thread::sleep);
    }

    DispatchService(ProviderQueryPort providerQuery,
                           DistributedLockPort lock,
                           JobOfferPort jobOffer,
                           BookingTransitionPort bookingTransition,
                           NotificationPort notification,
                           MatchingWeightsStore weightsStore,
                           DispatchProperties properties,
                           ProviderAcceptedPublisher acceptedPublisher,
                           ProviderRejectedPublisher rejectedPublisher,
                           BookingCancellationPort cancellation,
                           Clock clock,
                           Sleeper sleeper) {
        this.providerQuery = providerQuery;
        this.lock = lock;
        this.jobOffer = jobOffer;
        this.bookingTransition = bookingTransition;
        this.notification = notification;
        this.weightsStore = weightsStore;
        this.properties = properties;
        this.acceptedPublisher = acceptedPublisher;
        this.rejectedPublisher = rejectedPublisher;
        this.cancellation = cancellation;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Runs the full matching + offer + radius-expansion loop for a booking.
     *
     * @return the accepted provider's id, or {@code null} if the search failed after all cycles or
     *         was abandoned because the booking no longer needs a provider
     */
    public UUID dispatch(DispatchRequest request) {
        try {
            return search(request);
        } catch (BookingNotSearchableException e) {
            log.info("Stopped dispatch for booking {}: it is no longer searching for a provider ({})",
                    request.bookingId(), e.getMessage());
            return null;
        }
    }

    private UUID search(DispatchRequest request) {
        MatchingWeights weights = weightsStore.current();
        Duration timeout = Duration.ofSeconds(properties.getOfferTimeoutSeconds());

        // Providers already offered (and declined/timed-out) are removed from the pool for this
        // booking so an expanded radius never re-offers to someone who already said no (Req 8.7).
        Set<UUID> exhausted = new HashSet<>();

        // cycle 0 = initial radius; cycles 1..maxExpansionCycles = expansions (Req 8.8).
        int totalCycles = properties.getMaxExpansionCycles();
        for (int cycle = 0; cycle <= totalCycles; cycle++) {
            double radiusKm = radiusForCycle(cycle);
            log.debug("Dispatch booking {} cycle {} radius {} km", request.bookingId(), cycle, radiusKm);

            List<ProviderCandidate> pending = rankedCandidates(request, radiusKm, weights, exhausted);
            Instant busyDeadline = null;
            while (!pending.isEmpty()) {
                List<ProviderCandidate> busy = new ArrayList<>();
                for (ProviderCandidate candidate : pending) {
                    ensureStillSearching(request);
                    OfferAttempt attempt = tryOffer(request, candidate, timeout);
                    if (attempt.acceptedBy() != null) {
                        return attempt.acceptedBy();
                    }
                    if (attempt.busy()) {
                        busy.add(candidate);
                    } else {
                        // Declined or timed out: never offer this provider again for this booking.
                        exhausted.add(candidate.providerId());
                    }
                }
                if (busy.isEmpty()) {
                    break;
                }
                // Busy providers are mid-offer for another booking. Their lock lives at most one
                // offer window, so waiting that long gives each of them a chance to see this job;
                // skipping them outright failed a booking in milliseconds whenever the only nearby
                // provider was answering someone else.
                Instant now = clock.instant();
                if (busyDeadline == null) {
                    busyDeadline = now.plus(timeout);
                }
                if (!now.isBefore(busyDeadline) || !pause(busyDeadline, now)) {
                    log.debug("Dispatch booking {} cycle {}: {} provider(s) still busy; moving on",
                            request.bookingId(), cycle, busy.size());
                    break;
                }
                pending = busy;
            }
        }

        // Every candidate across every cycle declined or timed out (Requirement 8.9).
        return searchFailed(request);
    }

    /**
     * Sleeps one poll interval, or until the deadline if that is sooner.
     *
     * @return {@code false} if interrupted, in which case the caller stops waiting
     */
    private boolean pause(Instant deadline, Instant now) {
        Duration poll = Duration.ofMillis(properties.getOfferPollIntervalMillis());
        Duration remaining = Duration.between(now, deadline);
        try {
            sleeper.sleep(remaining.compareTo(poll) < 0 ? remaining : poll);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Throws {@link BookingNotSearchableException} once the booking has been cancelled. */
    private void ensureStillSearching(DispatchRequest request) {
        if (cancellation.isCancelled(request.bookingId())) {
            throw new BookingNotSearchableException(request.bookingId(),
                    "BookingCancelled received for booking " + request.bookingId(), null);
        }
    }

    /** Radius for a given cycle: initial for cycle 0, then +increment per expansion. */
    private double radiusForCycle(int cycle) {
        return properties.getInitialRadiusKm() + cycle * properties.getRadiusIncrementKm();
    }

    /**
     * Queries eligible providers, drops any already exhausted for this booking, and orders them by
     * matching score descending (Requirements 8.2, 8.3).
     */
    private List<ProviderCandidate> rankedCandidates(DispatchRequest request,
                                                     double radiusKm,
                                                     MatchingWeights weights,
                                                     Set<UUID> exhausted) {
        List<ProviderCandidate> candidates =
                new ArrayList<>(providerQuery.findEligibleProviders(request, radiusKm));
        candidates.removeIf(c -> exhausted.contains(c.providerId()));
        candidates.sort(Comparator.comparingDouble((ProviderCandidate c) -> c.score(weights)).reversed());
        return candidates;
    }

    /**
     * Attempts to offer the job to one candidate under an exclusive lock (Requirements 8.5, 8.11).
     *
     * @return who accepted, or that the provider was busy with another offer, or neither when the
     *         provider declined or let the offer time out
     */
    private OfferAttempt tryOffer(DispatchRequest request, ProviderCandidate candidate, Duration timeout) {
        DistributedLockPort.LockHandle handle = lock.tryAcquire(candidate.providerId(), timeout);
        if (handle == null) {
            // Another concurrent booking flow is already offering this provider (Req 8.11).
            log.debug("Provider {} already locked by a concurrent offer; skipping for booking {}",
                    candidate.providerId(), request.bookingId());
            return OfferAttempt.BUSY;
        }
        try {
            JobOfferPort.OfferOutcome outcome =
                    jobOffer.offer(request, candidate.providerId(), timeout);
            if (outcome == JobOfferPort.OfferOutcome.ACCEPTED) {
                return new OfferAttempt(accept(request, candidate.providerId()), false);
            }
            log.debug("Provider {} responded {} for booking {}",
                    candidate.providerId(), outcome, request.bookingId());
            // A cancellation withdraws the pending offer, which reads as a timeout: that is not a
            // provider rejection and must not be announced as one.
            ensureStillSearching(request);
            reject(request, candidate.providerId(), outcome);
            return OfferAttempt.NOT_ACCEPTED;
        } finally {
            lock.release(handle);
        }
    }

    /** Applies the acceptance transition and publishes the event (Requirement 8.6). */
    private UUID accept(DispatchRequest request, UUID providerId) {
        bookingTransition.markProviderAccepted(request.bookingId(), providerId);
        acceptedPublisher.publish(
                request.bookingId(), request.customerId(), providerId, request.bookingCreatedAt());
        log.info("Booking {} accepted by provider {}", request.bookingId(), providerId);
        return providerId;
    }

    /**
     * Publishes ProviderRejected for a declined or unanswered offer (Requirement 8.7).
     *
     * <p>Best-effort by design: the event informs downstream consumers but does not drive the
     * booking, so an outbox write failure is logged and the search carries on to the next
     * candidate rather than abandoning a booking that is still waiting for a provider.
     */
    private void reject(DispatchRequest request, UUID providerId, JobOfferPort.OfferOutcome outcome) {
        String reason = outcome == JobOfferPort.OfferOutcome.REJECTED
                ? ProviderRejectedEvent.REASON_REJECTED
                : ProviderRejectedEvent.REASON_TIMED_OUT;
        try {
            rejectedPublisher.publish(request.bookingId(), request.customerId(), providerId, reason);
        } catch (RuntimeException e) {
            log.warn("Could not publish ProviderRejected for booking {} provider {} ({}); continuing"
                    + " the search: {}", request.bookingId(), providerId, reason, e.toString());
        }
    }

    /** Handles the all-cycles-exhausted terminal path (Requirement 8.9). */
    private UUID searchFailed(DispatchRequest request) {
        log.warn("Dispatch exhausted all radius cycles for booking {}; marking SEARCHING_FAILED",
                request.bookingId());
        bookingTransition.markSearchingFailed(request.bookingId());
        notification.notifyCustomerNoProviderAvailable(request.bookingId(), request.customerId());
        notification.alertDispatcherTeam(request.bookingId());
        return null;
    }

    /** Result of one offer attempt: who accepted, or whether the provider was busy elsewhere. */
    private record OfferAttempt(UUID acceptedBy, boolean busy) {
        static final OfferAttempt BUSY = new OfferAttempt(null, true);
        static final OfferAttempt NOT_ACCEPTED = new OfferAttempt(null, false);
    }
}
