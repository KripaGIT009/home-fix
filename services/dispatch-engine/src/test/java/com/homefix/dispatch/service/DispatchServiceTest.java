package com.homefix.dispatch.service;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.domain.ScoreComponents;
import com.homefix.dispatch.port.JobOfferPort.OfferOutcome;
import com.homefix.dispatch.service.fake.FlagCancellation;
import com.homefix.dispatch.service.fake.InMemoryLock;
import com.homefix.dispatch.service.fake.MutableClock;
import com.homefix.dispatch.service.fake.RecordingBookingTransition;
import com.homefix.dispatch.service.fake.RecordingNotification;
import com.homefix.dispatch.event.ProviderRejectedEvent;
import com.homefix.dispatch.service.fake.RecordingProviderAcceptedPublisher;
import com.homefix.dispatch.service.fake.RecordingProviderRejectedPublisher;
import com.homefix.dispatch.service.fake.RecordingProviderRejectedPublisher.Rejection;
import com.homefix.dispatch.service.fake.ScriptedJobOffer;
import com.homefix.dispatch.service.fake.ScriptedProviderQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural unit tests for the dispatch orchestration (Requirements 8.2-8.11) using deterministic
 * in-memory fakes for every port.
 */
class DispatchServiceTest {

    private ScriptedProviderQuery providerQuery;
    private InMemoryLock lock;
    private ScriptedJobOffer jobOffer;
    private RecordingBookingTransition bookingTransition;
    private RecordingNotification notification;
    private RecordingProviderAcceptedPublisher acceptedPublisher;
    private RecordingProviderRejectedPublisher rejectedPublisher;
    private MatchingWeightsStore weightsStore;
    private DispatchProperties properties;
    private FlagCancellation cancellation;
    private MutableClock clock;
    /** Runs on every pause between attempts on busy providers; the clock has already moved. */
    private Runnable onPause;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        providerQuery = new ScriptedProviderQuery();
        lock = new InMemoryLock();
        jobOffer = new ScriptedJobOffer(lock);
        bookingTransition = new RecordingBookingTransition();
        notification = new RecordingNotification();
        acceptedPublisher = new RecordingProviderAcceptedPublisher();
        rejectedPublisher = new RecordingProviderRejectedPublisher();
        weightsStore = new MatchingWeightsStore();
        properties = new DispatchProperties(); // defaults: 10 km, +5 km, 3 cycles, 60 s
        cancellation = new FlagCancellation();
        clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
        onPause = () -> { };

        service = newService(rejectedPublisher);
    }

    private DispatchService newService(RecordingProviderRejectedPublisher rejections) {
        return new DispatchService(providerQuery, lock, jobOffer, bookingTransition,
                notification, weightsStore, properties, acceptedPublisher, rejections, cancellation,
                clock, pause -> {
                    clock.advance(pause);
                    onPause.run();
                });
    }

    private static DispatchRequest emergencyRequest() {
        return new DispatchRequest(UUID.randomUUID(), UUID.randomUUID(),
                12.9716, 77.5946, UUID.randomUUID(), List.of("plumbing"), true, Instant.now());
    }

    private static ProviderCandidate candidate(double distanceScore) {
        // Only the distance component varies so ranking order is fully determined by it.
        return new ProviderCandidate(UUID.randomUUID(),
                new ScoreComponents(distanceScore, 0.5, 0.5, 0.5, 0.5));
    }

    @Test
    void offersHighestScoringProviderFirstAndAcceptsIt() {
        ProviderCandidate best = candidate(0.9);
        ProviderCandidate middle = candidate(0.6);
        ProviderCandidate worst = candidate(0.3);
        providerQuery.whenRadiusAtLeast(10.0, worst, best, middle);
        jobOffer.respond(best.providerId(), OfferOutcome.ACCEPTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(best.providerId());
        assertThat(jobOffer.offeredProviders()).first().isEqualTo(best.providerId());
        assertThat(bookingTransition.acceptedProviderId()).isEqualTo(best.providerId());
        assertThat(acceptedPublisher.published()).isTrue();
        assertThat(notification.customerNotifications()).isZero();
    }

    @Test
    void skipsRejectingProviderAndOffersNextHighest() {
        ProviderCandidate best = candidate(0.9);
        ProviderCandidate second = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, best, second);
        jobOffer.respond(best.providerId(), OfferOutcome.REJECTED);
        jobOffer.respond(second.providerId(), OfferOutcome.ACCEPTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(second.providerId());
        assertThat(jobOffer.offeredProviders())
                .containsExactly(best.providerId(), second.providerId());
    }

    @Test
    void holdsExclusiveLockDuringEveryOfferAndReleasesAfterwards() {
        ProviderCandidate best = candidate(0.9);
        ProviderCandidate second = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, best, second);
        jobOffer.respond(best.providerId(), OfferOutcome.REJECTED);
        jobOffer.respond(second.providerId(), OfferOutcome.ACCEPTED);

        service.dispatch(emergencyRequest());

        // Requirement 8.11: the provider's lock is held at the moment of each offer.
        assertThat(jobOffer.lockHeldAtEveryOffer()).isTrue();
        // Locks are released after each offer so the providers are free again.
        assertThat(lock.isHeld(best.providerId())).isFalse();
        assertThat(lock.isHeld(second.providerId())).isFalse();
    }

    @Test
    void doesNotOfferProviderAlreadyLockedByConcurrentFlow() {
        ProviderCandidate locked = candidate(0.9);
        ProviderCandidate free = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, locked, free);
        // Simulate a concurrent booking already holding the top provider's exclusive lock.
        lock.forceHold(locked.providerId());
        jobOffer.respond(free.providerId(), OfferOutcome.ACCEPTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(free.providerId());
        // The locked provider was never offered (Requirement 8.11).
        assertThat(jobOffer.offeredProviders()).containsExactly(free.providerId());
    }

    @Test
    void waitsForAProviderBusyWithAnotherOfferInsteadOfFailingTheBooking() {
        // The only nearby provider is answering another booking's offer. Before the fix this run
        // skipped them in every radius and marked the booking SEARCHING_FAILED in milliseconds.
        ProviderCandidate busy = candidate(0.9);
        providerQuery.whenRadiusAtLeast(10.0, busy);
        lock.forceHold(busy.providerId());
        jobOffer.respond(busy.providerId(), OfferOutcome.ACCEPTED);
        Instant start = clock.instant();
        onPause = () -> {
            // The other booking's offer closes 20 s in.
            if (!clock.instant().isBefore(start.plusSeconds(20))) {
                lock.forceRelease(busy.providerId());
            }
        };

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(busy.providerId());
        assertThat(jobOffer.offeredProviders()).containsExactly(busy.providerId());
        assertThat(providerQuery.queriedRadii()).containsExactly(10.0);
        assertThat(bookingTransition.searchingFailedCalled()).isFalse();
        assertThat(rejectedPublisher.rejections()).isEmpty();
    }

    @Test
    void offersFreeProvidersBeforeWaitingOnBusyOnes() {
        ProviderCandidate busy = candidate(0.9);
        ProviderCandidate free = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, busy, free);
        lock.forceHold(busy.providerId());
        jobOffer.respond(free.providerId(), OfferOutcome.REJECTED);
        jobOffer.respond(busy.providerId(), OfferOutcome.ACCEPTED);
        onPause = () -> lock.forceRelease(busy.providerId());

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(busy.providerId());
        assertThat(jobOffer.offeredProviders()).containsExactly(free.providerId(), busy.providerId());
    }

    @Test
    void waitsAtMostOneOfferWindowPerRadiusForAProviderWhoStaysBusy() {
        ProviderCandidate busy = candidate(0.9);
        providerQuery.whenRadiusAtLeast(10.0, busy);
        lock.forceHold(busy.providerId());
        Instant start = clock.instant();

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isNull();
        assertThat(jobOffer.offeredProviders()).isEmpty();
        // Still busy is not a decline: every radius retried them, each for one 60 s window.
        assertThat(providerQuery.queriedRadii()).containsExactly(10.0, 15.0, 20.0, 25.0);
        assertThat(Duration.between(start, clock.instant())).isEqualTo(Duration.ofSeconds(4 * 60));
        assertThat(bookingTransition.searchingFailedCalled()).isTrue();
        assertThat(rejectedPublisher.rejections()).isEmpty();
    }

    @Test
    void expandsRadiusUpToThreeCyclesThenFindsProvider() {
        // Nobody within 10 km; a provider appears only once the radius reaches 20 km (cycle 2).
        ProviderCandidate farProvider = candidate(0.8);
        providerQuery.whenRadiusAtLeast(20.0, farProvider);
        jobOffer.respond(farProvider.providerId(), OfferOutcome.ACCEPTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(farProvider.providerId());
        // Radii queried: 10 (cycle0), 15 (cycle1), 20 (cycle2) — stops when accepted.
        assertThat(providerQuery.queriedRadii()).containsExactly(10.0, 15.0, 20.0);
    }

    @Test
    void stopsAfterMaxExpansionCyclesAndMarksSearchingFailed() {
        // No providers ever eligible: every cycle exhausts.
        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isNull();
        // Initial + 3 expansions = radii 10, 15, 20, 25 (Requirement 8.8: max 3 cycles).
        assertThat(providerQuery.queriedRadii()).containsExactly(10.0, 15.0, 20.0, 25.0);
        assertThat(bookingTransition.searchingFailedCalled()).isTrue();
        assertThat(bookingTransition.acceptedCalled()).isFalse();
    }

    @Test
    void searchingFailedNotifiesCustomerAndDispatcher() {
        DispatchRequest request = emergencyRequest();

        service.dispatch(request);

        assertThat(notification.customerNotifications()).isEqualTo(1);
        assertThat(notification.dispatcherAlerts()).isEqualTo(1);
        assertThat(notification.lastCustomerId()).isEqualTo(request.customerId());
        assertThat(acceptedPublisher.published()).isFalse();
    }

    @Test
    void doesNotReofferAProviderThatDeclinedInAnEarlierRadius() {
        // Provider eligible from the initial radius but declines; when the radius expands it is
        // still eligible but must not be offered again (Requirement 8.7).
        ProviderCandidate decliner = candidate(0.9);
        providerQuery.whenRadiusAtLeast(10.0, decliner);
        jobOffer.respond(decliner.providerId(), OfferOutcome.REJECTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isNull();
        // Offered exactly once despite being eligible in all four cycles.
        assertThat(jobOffer.offeredProviders()).containsExactly(decliner.providerId());
        assertThat(bookingTransition.searchingFailedCalled()).isTrue();
    }

    @Test
    void usesActiveCustomWeightsForRanking() {
        // Put all weight on availability. The provider with the higher availability score should be
        // offered first, even though it has a lower distance score.
        weightsStore.update(MatchingWeights.of(0.0, 1.0, 0.0, 0.0, 0.0));
        UUID hiAvailId = UUID.randomUUID();
        UUID hiDistId = UUID.randomUUID();
        ProviderCandidate highAvailability = new ProviderCandidate(hiAvailId,
                new ScoreComponents(0.1, 0.95, 0.1, 0.1, 0.1));
        ProviderCandidate highDistance = new ProviderCandidate(hiDistId,
                new ScoreComponents(0.99, 0.10, 0.1, 0.1, 0.1));
        providerQuery.whenRadiusAtLeast(10.0, highDistance, highAvailability);
        jobOffer.respond(hiAvailId, OfferOutcome.ACCEPTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(hiAvailId);
        assertThat(jobOffer.offeredProviders()).first().isEqualTo(hiAvailId);
    }

    // ---- ProviderRejected (Requirement 8.7) ----------------------------------------------------

    @Test
    void declinedOfferPublishesProviderRejectedWithBookingCustomerProviderAndReason() {
        ProviderCandidate decliner = candidate(0.9);
        ProviderCandidate acceptor = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, decliner, acceptor);
        jobOffer.respond(decliner.providerId(), OfferOutcome.REJECTED);
        jobOffer.respond(acceptor.providerId(), OfferOutcome.ACCEPTED);
        DispatchRequest request = emergencyRequest();

        service.dispatch(request);

        assertThat(rejectedPublisher.rejections()).containsExactly(new Rejection(
                request.bookingId(), request.customerId(), decliner.providerId(),
                ProviderRejectedEvent.REASON_REJECTED));
    }

    @Test
    void unansweredOfferPublishesProviderRejectedWithTimedOutReason() {
        ProviderCandidate silent = candidate(0.9);
        providerQuery.whenRadiusAtLeast(10.0, silent);
        // ScriptedJobOffer answers TIMED_OUT for any provider not scripted.
        DispatchRequest request = emergencyRequest();

        service.dispatch(request);

        // Offered once (never re-offered on expansion), so exactly one rejection.
        assertThat(rejectedPublisher.rejections()).containsExactly(new Rejection(
                request.bookingId(), request.customerId(), silent.providerId(),
                ProviderRejectedEvent.REASON_TIMED_OUT));
    }

    @Test
    void acceptedOfferAndSkippedLockedProviderPublishNoRejection() {
        ProviderCandidate locked = candidate(0.9);
        ProviderCandidate acceptor = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, locked, acceptor);
        lock.forceHold(locked.providerId());
        jobOffer.respond(acceptor.providerId(), OfferOutcome.ACCEPTED);

        service.dispatch(emergencyRequest());

        // The locked provider was never offered, and the acceptor accepted: nobody rejected.
        assertThat(rejectedPublisher.rejections()).isEmpty();
    }

    @Test
    void failedRejectionPublishDoesNotAbortTheSearch() {
        service = newService(new RecordingProviderRejectedPublisher().failing());
        ProviderCandidate decliner = candidate(0.9);
        ProviderCandidate acceptor = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, decliner, acceptor);
        jobOffer.respond(decliner.providerId(), OfferOutcome.REJECTED);
        jobOffer.respond(acceptor.providerId(), OfferOutcome.ACCEPTED);

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isEqualTo(acceptor.providerId());
        assertThat(acceptedPublisher.published()).isTrue();
    }

    // ---- Cancelled booking stops the search (Requirement 8.7) ----------------------------------

    @Test
    void cancellationDuringAnOfferStopsTheSearchWithoutRejectionsOrFailureNotices() {
        ProviderCandidate first = candidate(0.9);
        ProviderCandidate second = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, first, second);
        // The customer cancels while the first offer is outstanding; the withdrawn offer reads as
        // a timeout.
        jobOffer.onOffer((booking, provider) -> cancellation.markCancelled(booking));

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isNull();
        assertThat(jobOffer.offeredProviders()).containsExactly(first.providerId());
        assertThat(rejectedPublisher.rejections()).isEmpty();
        assertThat(bookingTransition.searchingFailedCalled()).isFalse();
        assertThat(notification.customerNotifications()).isZero();
        assertThat(notification.dispatcherAlerts()).isZero();
    }

    @Test
    void alreadyCancelledBookingIsNeverOffered() {
        providerQuery.whenRadiusAtLeast(10.0, candidate(0.9));
        DispatchRequest request = emergencyRequest();
        cancellation.markCancelled(request.bookingId());

        assertThat(service.dispatch(request)).isNull();

        assertThat(jobOffer.offeredProviders()).isEmpty();
        assertThat(bookingTransition.searchingFailedCalled()).isFalse();
    }

    @Test
    void acceptanceRefusedByBookingServiceEndsTheSearchWithoutPublishingProviderAccepted() {
        ProviderCandidate acceptor = candidate(0.9);
        ProviderCandidate next = candidate(0.6);
        providerQuery.whenRadiusAtLeast(10.0, acceptor, next);
        jobOffer.respond(acceptor.providerId(), OfferOutcome.ACCEPTED);
        bookingTransition.refuseAsNotSearchable();

        UUID accepted = service.dispatch(emergencyRequest());

        assertThat(accepted).isNull();
        assertThat(acceptedPublisher.published()).isFalse();
        assertThat(jobOffer.offeredProviders()).containsExactly(acceptor.providerId());
    }

    @Test
    void searchingFailedRefusedByBookingServiceSkipsTheNoProviderNotices() {
        bookingTransition.refuseAsNotSearchable();

        assertThat(service.dispatch(emergencyRequest())).isNull();

        assertThat(notification.customerNotifications()).isZero();
        assertThat(notification.dispatcherAlerts()).isZero();
    }
}
