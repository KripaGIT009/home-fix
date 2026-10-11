package com.homefix.dispatch.adapter;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferRound;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferPort;
import com.homefix.dispatch.port.NotificationPort;
import com.homefix.dispatch.service.JobOfferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Default {@link JobOfferPort} adapter (Requirements 8.5-8.7). The Dispatch Engine owns the offers:
 * it records a wave of PENDING offers through {@link JobOfferService} (Redis-backed, so any
 * instance's provider API can see and decide them), nudges each provider with a best-effort push,
 * and then waits for answers, which arrive through {@code POST /dispatch/offers/{bookingId}/accept}
 * or {@code /decline}.
 *
 * <p>Waiting is a short-interval poll of the booking's offers. It ends as soon as one provider in
 * the wave has accepted (the same write withdrew the others) or everyone has answered. When the
 * window closes the remaining offers are expired by compare-and-set: a decision that landed first
 * is honoured, otherwise the offer becomes EXPIRED and any later accept is refused.
 *
 * <p>Failure handling is "treat it as a timeout": if the wave cannot be recorded nobody can answer
 * it, and the dispatch loop simply moves on to the next providers. Pushes run on a separate
 * executor so a slow or missing Notification Service never eats into the window.
 *
 * <p>Registered by {@code DispatchAdaptersConfig}.
 */
public class PollingJobOfferAdapter implements JobOfferPort {

    private static final Logger log = LoggerFactory.getLogger(PollingJobOfferAdapter.class);

    /** Pause between status reads; abstracted so tests can drive time deterministically. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final JobOfferService offers;
    private final NotificationPort notification;
    private final Executor notificationExecutor;
    private final Clock clock;
    private final Duration pollInterval;
    private final Sleeper sleeper;

    public PollingJobOfferAdapter(JobOfferService offers,
                                  NotificationPort notification,
                                  Executor notificationExecutor,
                                  Clock clock,
                                  Duration pollInterval,
                                  Sleeper sleeper) {
        this.offers = offers;
        this.notification = notification;
        this.notificationExecutor = notificationExecutor;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.sleeper = sleeper;
    }

    @Override
    public WaveOutcome offer(DispatchRequest request, List<UUID> providerIds, Duration timeout) {
        UUID bookingId = request.bookingId();
        List<JobOffer> opened;
        try {
            opened = offers.open(request, providerIds, timeout);
        } catch (RuntimeException e) {
            log.warn("Could not record the job offers for booking {}; treating them as timed out: {}",
                    bookingId, e.toString());
            return WaveOutcome.noAnswer(providerIds);
        }
        opened.forEach(this::pushBestEffort);
        Instant expiresAt = opened.stream().map(JobOffer::expiresAt).max(Instant::compareTo)
                .orElse(clock.instant());

        try {
            Instant now;
            while ((now = clock.instant()).isBefore(expiresAt)) {
                // null is a storage hiccup: keep waiting and let the closing compare-and-set decide.
                Optional<OfferRound> round = readRound(bookingId);
                if (round != null) {
                    if (round.isEmpty()) {
                        // Gone from storage: nobody can answer any more.
                        return WaveOutcome.noAnswer(providerIds);
                    }
                    if (acceptedInWave(round.get(), providerIds) != null || allAnswered(round.get(), providerIds)) {
                        return outcomeOf(round.get(), providerIds);
                    }
                }
                Duration remaining = Duration.between(now, expiresAt);
                sleeper.sleep(remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted waiting on the offers of booking {}; closing them", bookingId);
        }
        return close(bookingId, providerIds);
    }

    /** @return the round, empty when it is gone, or {@code null} when it could not be read */
    private Optional<OfferRound> readRound(UUID bookingId) {
        try {
            return offers.round(bookingId);
        } catch (RuntimeException e) {
            log.debug("Could not read the offers of booking {}: {}", bookingId, e.toString());
            return null;
        }
    }

    /** Expires the wave's pending offers, keeping any decision that landed first. */
    private WaveOutcome close(UUID bookingId, List<UUID> providerIds) {
        try {
            OfferRound last = offers.expire(bookingId, providerIds);
            log.debug("Offers of booking {} to {} provider(s) closed", bookingId, providerIds.size());
            return outcomeOf(last, providerIds);
        } catch (RuntimeException e) {
            log.warn("Could not close the offers of booking {}; treating them as timed out: {}",
                    bookingId, e.toString());
            return WaveOutcome.noAnswer(providerIds);
        }
    }

    private static UUID acceptedInWave(OfferRound round, List<UUID> providerIds) {
        return round.accepted()
                .map(JobOffer::providerId)
                .filter(providerIds::contains)
                .orElse(null);
    }

    private static boolean allAnswered(OfferRound round, List<UUID> providerIds) {
        return providerIds.stream().allMatch(p -> round.offerTo(p).map(o -> o.status().isTerminal()).orElse(true));
    }

    static WaveOutcome outcomeOf(OfferRound round, List<UUID> providerIds) {
        Map<UUID, OfferOutcome> outcomes = new LinkedHashMap<>();
        for (UUID providerId : providerIds) {
            outcomes.put(providerId, round.offerTo(providerId)
                    .map(o -> outcomeOf(o.status()))
                    .orElse(OfferOutcome.TIMED_OUT));
        }
        return new WaveOutcome(acceptedInWave(round, providerIds), outcomes);
    }

    private void pushBestEffort(JobOffer offer) {
        Runnable push = () -> {
            try {
                notification.notifyProviderOfJobOffer(offer.bookingId(), offer.providerId(), offer.expiresAt());
            } catch (RuntimeException e) {
                log.warn("Job-offer push to provider {} for booking {} failed; the offer stands: {}",
                        offer.providerId(), offer.bookingId(), e.toString());
            }
        };
        try {
            notificationExecutor.execute(push);
        } catch (RuntimeException e) {
            log.warn("Could not schedule the job-offer push for booking {}: {}", offer.bookingId(), e.toString());
        }
    }

    static OfferOutcome outcomeOf(OfferStatus status) {
        return switch (status) {
            case ACCEPTED -> OfferOutcome.ACCEPTED;
            case DECLINED -> OfferOutcome.REJECTED;
            // EXPIRED, WITHDRAWN, and (defensively) a still-PENDING offer all mean "no answer".
            default -> OfferOutcome.TIMED_OUT;
        };
    }
}
