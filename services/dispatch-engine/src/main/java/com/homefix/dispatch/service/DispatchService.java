package com.homefix.dispatch.service;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.port.BookingTransitionPort;
import com.homefix.dispatch.port.DistributedLockPort;
import com.homefix.dispatch.port.JobOfferPort;
import com.homefix.dispatch.port.NotificationPort;
import com.homefix.dispatch.port.ProviderQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 *   <li>on acceptance, transitions the booking to PROVIDER_ACCEPTED and publishes ProviderAccepted
 *       (Requirement 8.6);</li>
 *   <li>on reject/timeout, drops that provider from the pool and moves to the next
 *       (Requirement 8.7);</li>
 *   <li>when a radius is exhausted with no acceptance, expands by the configured increment up to
 *       the cycle limit (Requirement 8.8);</li>
 *   <li>if all cycles are exhausted, transitions the booking to SEARCHING_FAILED and fires the
 *       customer + dispatcher notifications (Requirement 8.9).</li>
 * </ol>
 *
 * <p>All external effects are behind ports so the whole algorithm is exercised with deterministic
 * fakes in unit tests. The class is stateless and thread-safe: it is invoked concurrently from the
 * emergency and scheduled bulkhead thread pools.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final ProviderQueryPort providerQuery;
    private final DistributedLockPort lock;
    private final JobOfferPort jobOffer;
    private final BookingTransitionPort bookingTransition;
    private final NotificationPort notification;
    private final MatchingWeightsStore weightsStore;
    private final DispatchProperties properties;
    private final ProviderAcceptedPublisher acceptedPublisher;

    public DispatchService(ProviderQueryPort providerQuery,
                           DistributedLockPort lock,
                           JobOfferPort jobOffer,
                           BookingTransitionPort bookingTransition,
                           NotificationPort notification,
                           MatchingWeightsStore weightsStore,
                           DispatchProperties properties,
                           ProviderAcceptedPublisher acceptedPublisher) {
        this.providerQuery = providerQuery;
        this.lock = lock;
        this.jobOffer = jobOffer;
        this.bookingTransition = bookingTransition;
        this.notification = notification;
        this.weightsStore = weightsStore;
        this.properties = properties;
        this.acceptedPublisher = acceptedPublisher;
    }

    /**
     * Runs the full matching + offer + radius-expansion loop for a booking.
     *
     * @return the accepted provider's id, or {@code null} if the search failed after all cycles
     */
    public UUID dispatch(DispatchRequest request) {
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

            List<ProviderCandidate> ranked = rankedCandidates(request, radiusKm, weights, exhausted);
            for (ProviderCandidate candidate : ranked) {
                UUID accepted = tryOffer(request, candidate, timeout);
                if (accepted != null) {
                    return accepted;
                }
                // Declined or timed out: never offer this provider again for this booking.
                exhausted.add(candidate.providerId());
            }
        }

        // Every candidate across every cycle declined or timed out (Requirement 8.9).
        return searchFailed(request);
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
     * @return the accepted provider id, or {@code null} if not acquired / declined / timed out
     */
    private UUID tryOffer(DispatchRequest request, ProviderCandidate candidate, Duration timeout) {
        DistributedLockPort.LockHandle handle = lock.tryAcquire(candidate.providerId(), timeout);
        if (handle == null) {
            // Another concurrent booking flow is already offering this provider (Req 8.11).
            log.debug("Provider {} already locked by a concurrent offer; skipping for booking {}",
                    candidate.providerId(), request.bookingId());
            return null;
        }
        try {
            JobOfferPort.OfferOutcome outcome =
                    jobOffer.offer(request.bookingId(), candidate.providerId(), timeout);
            if (outcome == JobOfferPort.OfferOutcome.ACCEPTED) {
                return accept(request, candidate.providerId());
            }
            log.debug("Provider {} responded {} for booking {}",
                    candidate.providerId(), outcome, request.bookingId());
            return null;
        } finally {
            lock.release(handle);
        }
    }

    /** Applies the acceptance transition and publishes the event (Requirement 8.6). */
    private UUID accept(DispatchRequest request, UUID providerId) {
        bookingTransition.markProviderAccepted(request.bookingId(), providerId);
        acceptedPublisher.publish(request.bookingId(), providerId);
        log.info("Booking {} accepted by provider {}", request.bookingId(), providerId);
        return providerId;
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
}
