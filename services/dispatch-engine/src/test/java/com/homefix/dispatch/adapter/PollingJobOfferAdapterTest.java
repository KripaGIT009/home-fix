package com.homefix.dispatch.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.UnaryOperator;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferPort.OfferOutcome;
import com.homefix.dispatch.port.JobOfferPort.WaveOutcome;
import com.homefix.dispatch.port.JobOfferStore;
import com.homefix.dispatch.service.JobOfferService;
import com.homefix.dispatch.service.fake.InMemoryJobOfferStore;
import com.homefix.dispatch.service.fake.MutableClock;
import com.homefix.dispatch.service.fake.RecordingNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PollingJobOfferAdapter} driven on a hand-moved clock (Requirements 8.5-8.7). The sleeper
 * advances the clock instead of blocking, and can run "provider" actions mid-wait, so each test is
 * deterministic: a wave of offers is recorded and pushed, the adapter polls until someone accepts,
 * everyone has answered, or the window closes, and the closing compare-and-set decides between
 * last-instant answers and the timeout. Every storage or push failure degrades to "no answer",
 * never to an exception on the dispatch thread.
 */
class PollingJobOfferAdapterTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(500);

    private MutableClock clock;
    private FlakyStore store;
    private JobOfferService offers;
    private RecordingNotification notification;
    private final List<Duration> sleeps = new ArrayList<>();
    /** Runs at each sleep, before the clock moves; tests use it to act as the providers. */
    private Runnable duringSleep = () -> { };

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-02T10:00:00Z"));
        store = new FlakyStore(new InMemoryJobOfferStore());
        offers = new JobOfferService(store, clock);
        notification = new RecordingNotification();
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private PollingJobOfferAdapter adapter(Executor pushExecutor) {
        return new PollingJobOfferAdapter(offers, notification, pushExecutor, clock, POLL,
                duration -> {
                    sleeps.add(duration);
                    duringSleep.run();
                    clock.advance(duration);
                });
    }

    private PollingJobOfferAdapter adapter() {
        return adapter(Runnable::run);
    }

    private DispatchRequest request() {
        return new DispatchRequest(bookingId, UUID.randomUUID(), 12.9, 77.6, UUID.randomUUID(),
                List.of("plumbing"), true, clock.instant(), "HFX-2026-0000007", null);
    }

    private WaveOutcome offer(Duration window, UUID... providers) {
        return adapter().offer(request(), List.of(providers), window);
    }

    private Optional<OfferStatus> stored(UUID providerId) {
        return offers.statusOf(bookingId, providerId);
    }

    // ---- one provider --------------------------------------------------------------------------

    @Test
    void providerAcceptsDuringTheWindow_isAccepted() {
        duringSleep = () -> {
            if (sleeps.size() == 3) {
                offers.decide(bookingId, provider, OfferStatus.ACCEPTED);
            }
        };

        WaveOutcome outcome = offer(WINDOW, provider);

        assertThat(outcome.acceptedBy()).isEqualTo(provider);
        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(sleeps).hasSize(3).allMatch(POLL::equals);
        assertThat(notification.offerPushes()).containsExactly(provider);
    }

    @Test
    void providerDeclines_isRejectedWithoutWaitingOutTheWindow() {
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.DECLINED);

        WaveOutcome outcome = offer(WINDOW, provider);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.REJECTED);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void noAnswer_pollsUntilTheWindowClosesThenExpiresTheOffer() {
        WaveOutcome outcome = offer(Duration.ofMillis(1_200), provider);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.TIMED_OUT);
        // The last sleep is trimmed to the time left, so the wait never overshoots the window.
        assertThat(sleeps).containsExactly(POLL, POLL, Duration.ofMillis(200));
        assertThat(stored(provider)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void acceptInTheLastInstant_beatsTheTimeout() {
        // The accept lands during the final sleep; the loop exits on the deadline without reading
        // it, and the closing compare-and-set must honour it rather than expire the offer.
        duringSleep = () -> {
            if (sleeps.size() == 2) {
                offers.decide(bookingId, provider, OfferStatus.ACCEPTED);
            }
        };

        assertThat(offer(Duration.ofMillis(1_000), provider).acceptedBy()).isEqualTo(provider);
        assertThat(stored(provider)).contains(OfferStatus.ACCEPTED);
    }

    // ---- a wave --------------------------------------------------------------------------------

    @Test
    void wave_isOfferedToEveryProviderAtOnce() {
        offer(Duration.ofMillis(500), provider, second, third);

        assertThat(notification.offerPushes()).containsExactlyInAnyOrder(provider, second, third);
        assertThat(stored(provider)).contains(OfferStatus.EXPIRED);
        assertThat(stored(second)).contains(OfferStatus.EXPIRED);
        assertThat(stored(third)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void wave_firstAcceptTakesTheJobAndTheOthersAreWithdrawn() {
        duringSleep = () -> {
            if (sleeps.size() == 2) {
                offers.decide(bookingId, third, OfferStatus.ACCEPTED);
                offers.decide(bookingId, second, OfferStatus.ACCEPTED);
            }
        };

        WaveOutcome outcome = offer(WINDOW, provider, second, third);

        assertThat(outcome.acceptedBy()).isEqualTo(third);
        assertThat(outcome.outcomeOf(third)).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(outcome.outcomeOf(second)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(stored(provider)).contains(OfferStatus.WITHDRAWN);
        assertThat(stored(second)).contains(OfferStatus.WITHDRAWN);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void wave_endsAsSoonAsEveryoneHasDeclined() {
        duringSleep = () -> {
            offers.decide(bookingId, provider, OfferStatus.DECLINED);
            if (sleeps.size() == 2) {
                offers.decide(bookingId, second, OfferStatus.DECLINED);
            }
        };

        WaveOutcome outcome = offer(WINDOW, provider, second);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.REJECTED);
        assertThat(outcome.outcomeOf(second)).isEqualTo(OfferOutcome.REJECTED);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void wave_mixesDeclinesWithTimeoutsWhenTheWindowCloses() {
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.DECLINED);

        WaveOutcome outcome = offer(Duration.ofMillis(1_000), provider, second);

        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.REJECTED);
        assertThat(outcome.outcomeOf(second)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(stored(second)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void wave_recordsTheDisplayFieldsOnEveryOffer() {
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.ACCEPTED);

        offer(WINDOW, provider, second);

        assertThat(store.find(bookingId).orElseThrow().offers()).allSatisfy(o -> {
            assertThat(o.reference()).isEqualTo("HFX-2026-0000007");
            assertThat(o.emergency()).isTrue();
        });
    }

    // ---- interruptions and failures ------------------------------------------------------------

    @Test
    void withdrawnOffers_areNoAnswer() {
        duringSleep = () -> offers.withdraw(bookingId);

        WaveOutcome outcome = offer(WINDOW, provider, second);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(outcome.outcomes()).containsValues(OfferOutcome.TIMED_OUT);
        assertThat(sleeps).hasSize(1);
        assertThat(stored(provider)).contains(OfferStatus.WITHDRAWN);
    }

    @Test
    void offersGoneFromStorage_areNoAnswerWithoutWaitingOutTheWindow() {
        duringSleep = () -> store.delegate.evict(bookingId);

        assertThat(offer(WINDOW, provider).acceptedBy()).isNull();
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void offersThatCannotBeRecorded_areNoAnswerAndNobodyIsPushed() {
        store.failOpen = true;

        WaveOutcome outcome = offer(WINDOW, provider, second);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(outcome.outcomeOf(second)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(sleeps).isEmpty();
        assertThat(notification.offerPushes()).isEmpty();
    }

    @Test
    void failedPush_leavesTheOfferStanding() {
        NotificationThatFails failing = new NotificationThatFails();
        PollingJobOfferAdapter adapter = new PollingJobOfferAdapter(offers, failing, Runnable::run,
                clock, POLL, duration -> {
                    offers.decide(bookingId, provider, OfferStatus.ACCEPTED);
                    clock.advance(duration);
                });

        assertThat(adapter.offer(request(), List.of(provider), WINDOW).acceptedBy()).isEqualTo(provider);
        assertThat(failing.attempts).isEqualTo(1);
    }

    @Test
    void rejectedPushExecutor_leavesTheOfferStanding() {
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.ACCEPTED);

        WaveOutcome outcome = adapter(task -> {
            throw new RejectedExecutionException("shut down");
        }).offer(request(), List.of(provider), WINDOW);

        assertThat(outcome.acceptedBy()).isEqualTo(provider);
        assertThat(notification.offerPushes()).isEmpty();
    }

    @Test
    void pushesRunOnTheSuppliedExecutorNotTheDispatchThread() {
        List<Runnable> queued = new ArrayList<>();
        duringSleep = () -> {
            offers.decide(bookingId, provider, OfferStatus.DECLINED);
            offers.decide(bookingId, second, OfferStatus.DECLINED);
        };

        adapter(queued::add).offer(request(), List.of(provider, second), WINDOW);

        assertThat(notification.offerPushes()).isEmpty();
        queued.forEach(Runnable::run);
        assertThat(notification.offerPushes()).containsExactly(provider, second);
    }

    @Test
    void unreadableOffers_keepWaitingAndTheCloseStillDecides() {
        duringSleep = () -> {
            store.failFind = sleeps.size() < 2;
            if (sleeps.size() == 1) {
                offers.decide(bookingId, provider, OfferStatus.ACCEPTED);
            }
        };

        assertThat(offer(WINDOW, provider).acceptedBy()).isEqualTo(provider);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void interruptedWait_closesTheOffersAndKeepsTheInterruptFlag() {
        PollingJobOfferAdapter adapter = new PollingJobOfferAdapter(offers, notification, Runnable::run,
                clock, POLL, duration -> {
                    throw new InterruptedException("shutting down");
                });

        WaveOutcome outcome = adapter.offer(request(), List.of(provider, second), WINDOW);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(stored(provider)).contains(OfferStatus.EXPIRED);
        assertThat(stored(second)).contains(OfferStatus.EXPIRED);
    }

    @Test
    void closeThatFails_isNoAnswer() {
        duringSleep = () -> store.failUpdate = true;

        WaveOutcome outcome = offer(Duration.ofMillis(500), provider);

        assertThat(outcome.acceptedBy()).isNull();
        assertThat(outcome.outcomeOf(provider)).isEqualTo(OfferOutcome.TIMED_OUT);
    }

    @Test
    void outcomeMapping() {
        assertThat(PollingJobOfferAdapter.outcomeOf(OfferStatus.ACCEPTED)).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(PollingJobOfferAdapter.outcomeOf(OfferStatus.DECLINED)).isEqualTo(OfferOutcome.REJECTED);
        assertThat(PollingJobOfferAdapter.outcomeOf(OfferStatus.EXPIRED)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(PollingJobOfferAdapter.outcomeOf(OfferStatus.WITHDRAWN)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(PollingJobOfferAdapter.outcomeOf(OfferStatus.PENDING)).isEqualTo(OfferOutcome.TIMED_OUT);
    }

    /** Delegating store whose operations can be made to fail, as a Redis outage would. */
    private static final class FlakyStore implements JobOfferStore {
        final InMemoryJobOfferStore delegate;
        /** Fails writes that carry a TTL: opening a wave. */
        volatile boolean failOpen;
        volatile boolean failFind;
        /** Fails writes without a TTL: decisions, expiry and withdrawal. */
        volatile boolean failUpdate;

        FlakyStore(InMemoryJobOfferStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<OfferRound> find(UUID bookingId) {
            if (failFind) {
                throw new IllegalStateException("redis down");
            }
            return delegate.find(bookingId);
        }

        @Override
        public List<JobOffer> findByProvider(UUID providerId) {
            return delegate.findByProvider(providerId);
        }

        @Override
        public Change update(UUID bookingId, UnaryOperator<OfferRound> transition, Duration ttl) {
            if ((ttl != null && failOpen) || (ttl == null && failUpdate)) {
                throw new IllegalStateException("redis down");
            }
            return delegate.update(bookingId, transition, ttl);
        }
    }

    private static final class NotificationThatFails extends RecordingNotification {
        int attempts;

        @Override
        public synchronized void notifyProviderOfJobOffer(UUID bookingId, UUID providerId, Instant expiresAt) {
            attempts++;
            throw new IllegalStateException("notification-service unavailable");
        }
    }
}
