package com.homefix.dispatch.service;

import com.homefix.dispatch.config.DispatchProperties;
import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.MatchingWeights;
import com.homefix.dispatch.domain.MatchingWeightsStore;
import com.homefix.dispatch.domain.ProviderCandidate;
import com.homefix.dispatch.domain.ScoreComponents;
import com.homefix.dispatch.port.JobOfferPort.OfferOutcome;
import com.homefix.dispatch.service.fake.InMemoryLock;
import com.homefix.dispatch.service.fake.RecordingBookingTransition;
import com.homefix.dispatch.service.fake.RecordingNotification;
import com.homefix.dispatch.service.fake.RecordingProviderAcceptedPublisher;
import com.homefix.dispatch.service.fake.ScriptedJobOffer;
import com.homefix.dispatch.service.fake.ScriptedProviderQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private MatchingWeightsStore weightsStore;
    private DispatchProperties properties;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        providerQuery = new ScriptedProviderQuery();
        lock = new InMemoryLock();
        jobOffer = new ScriptedJobOffer(lock);
        bookingTransition = new RecordingBookingTransition();
        notification = new RecordingNotification();
        acceptedPublisher = new RecordingProviderAcceptedPublisher();
        weightsStore = new MatchingWeightsStore();
        properties = new DispatchProperties(); // defaults: 10 km, +5 km, 3 cycles, 60 s

        service = new DispatchService(providerQuery, lock, jobOffer, bookingTransition,
                notification, weightsStore, properties, acceptedPublisher);
    }

    private static DispatchRequest emergencyRequest() {
        return new DispatchRequest(UUID.randomUUID(), UUID.randomUUID(),
                12.9716, 77.5946, UUID.randomUUID(), List.of("plumbing"), true);
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
}
