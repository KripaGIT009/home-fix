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
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferPort.OfferOutcome;
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
 * advances the clock instead of blocking, and can run a "provider" action mid-wait, so each test
 * is deterministic: the offer is recorded and pushed, the adapter polls until the provider answers
 * or the window closes, and the closing compare-and-set decides between a last-instant answer and
 * the timeout. Every storage or push failure degrades to "no answer", never to an exception on the
 * dispatch thread.
 */
class PollingJobOfferAdapterTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(500);

    private MutableClock clock;
    private FlakyStore store;
    private JobOfferService offers;
    private RecordingNotification notification;
    private final List<Duration> sleeps = new ArrayList<>();
    /** Runs at each sleep, before the clock moves; tests use it to act as the provider. */
    private Runnable duringSleep = () -> { };

    private final UUID bookingId = UUID.randomUUID();
    private final UUID provider = UUID.randomUUID();

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

    private Optional<OfferStatus> stored() {
        return offers.statusOf(bookingId, provider);
    }

    @Test
    void providerAcceptsDuringTheWindow_isAccepted() {
        duringSleep = () -> {
            if (sleeps.size() == 3) {
                offers.decide(bookingId, provider, OfferStatus.ACCEPTED);
            }
        };

        OfferOutcome outcome = adapter().offer(bookingId, provider, WINDOW);

        assertThat(outcome).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(sleeps).hasSize(3).allMatch(POLL::equals);
        assertThat(notification.offerPushes()).containsExactly(provider);
    }

    @Test
    void providerDeclines_isRejected() {
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.DECLINED);

        assertThat(adapter().offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.REJECTED);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void noAnswer_pollsUntilTheWindowClosesThenExpiresTheOffer() {
        OfferOutcome outcome = adapter().offer(bookingId, provider, Duration.ofMillis(1_200));

        assertThat(outcome).isEqualTo(OfferOutcome.TIMED_OUT);
        // The last sleep is trimmed to the time left, so the wait never overshoots the window.
        assertThat(sleeps).containsExactly(POLL, POLL, Duration.ofMillis(200));
        assertThat(stored()).contains(OfferStatus.EXPIRED);
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

        assertThat(adapter().offer(bookingId, provider, Duration.ofMillis(1_000)))
                .isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(stored()).contains(OfferStatus.ACCEPTED);
    }

    @Test
    void withdrawnOffer_isNoAnswer() {
        duringSleep = () -> offers.withdraw(bookingId);

        assertThat(adapter().offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(sleeps).hasSize(1);
        assertThat(stored()).contains(OfferStatus.WITHDRAWN);
    }

    @Test
    void offerGoneFromStorage_isNoAnswerWithoutWaitingOutTheWindow() {
        duringSleep = () -> store.delegate.evict(bookingId);

        assertThat(adapter().offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void offerFromRequest_recordsTheDisplayFields() {
        DispatchRequest request = new DispatchRequest(bookingId, UUID.randomUUID(), 12.9, 77.6,
                UUID.randomUUID(), List.of("plumbing"), true, clock.instant(), "HFX-2026-0000007", null);
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.ACCEPTED);

        assertThat(adapter().offer(request, provider, WINDOW)).isEqualTo(OfferOutcome.ACCEPTED);
        JobOffer offer = store.find(bookingId).orElseThrow();
        assertThat(offer.reference()).isEqualTo("HFX-2026-0000007");
        assertThat(offer.emergency()).isTrue();
    }

    @Test
    void offerThatCannotBeRecorded_isNoAnswerAndNobodyIsPushed() {
        store.failSave = true;

        assertThat(adapter().offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.TIMED_OUT);
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

        assertThat(adapter.offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(failing.attempts).isEqualTo(1);
    }

    @Test
    void rejectedPushExecutor_leavesTheOfferStanding() {
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.ACCEPTED);

        OfferOutcome outcome = adapter(task -> {
            throw new RejectedExecutionException("shut down");
        }).offer(bookingId, provider, WINDOW);

        assertThat(outcome).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(notification.offerPushes()).isEmpty();
    }

    @Test
    void pushRunsOnTheSuppliedExecutorNotTheDispatchThread() {
        List<Runnable> queued = new ArrayList<>();
        duringSleep = () -> offers.decide(bookingId, provider, OfferStatus.DECLINED);

        adapter(queued::add).offer(bookingId, provider, WINDOW);

        assertThat(notification.offerPushes()).isEmpty();
        queued.forEach(Runnable::run);
        assertThat(notification.offerPushes()).containsExactly(provider);
    }

    @Test
    void unreadableStatus_keepsWaitingAndTheCloseStillDecides() {
        duringSleep = () -> {
            store.failFind = sleeps.size() < 2;
            if (sleeps.size() == 1) {
                offers.decide(bookingId, provider, OfferStatus.ACCEPTED);
            }
        };

        assertThat(adapter().offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.ACCEPTED);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void interruptedWait_closesTheOfferAndKeepsTheInterruptFlag() {
        PollingJobOfferAdapter adapter = new PollingJobOfferAdapter(offers, notification, Runnable::run,
                clock, POLL, duration -> {
                    throw new InterruptedException("shutting down");
                });

        assertThat(adapter.offer(bookingId, provider, WINDOW)).isEqualTo(OfferOutcome.TIMED_OUT);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(stored()).contains(OfferStatus.EXPIRED);
    }

    @Test
    void closeThatFails_isNoAnswer() {
        duringSleep = () -> store.failUpdate = true;

        assertThat(adapter().offer(bookingId, provider, Duration.ofMillis(500)))
                .isEqualTo(OfferOutcome.TIMED_OUT);
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
        volatile boolean failSave;
        volatile boolean failFind;
        volatile boolean failUpdate;

        FlakyStore(InMemoryJobOfferStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(JobOffer offer, Duration ttl) {
            if (failSave) {
                throw new IllegalStateException("redis down");
            }
            delegate.save(offer, ttl);
        }

        @Override
        public Optional<JobOffer> find(UUID bookingId) {
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
        public Optional<Change> update(UUID bookingId, UnaryOperator<JobOffer> transition) {
            if (failUpdate) {
                throw new IllegalStateException("redis down");
            }
            return delegate.update(bookingId, transition);
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
