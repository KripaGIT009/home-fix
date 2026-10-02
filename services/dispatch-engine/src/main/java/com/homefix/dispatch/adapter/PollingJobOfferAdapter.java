package com.homefix.dispatch.adapter;

import com.homefix.dispatch.domain.DispatchRequest;
import com.homefix.dispatch.domain.JobOffer;
import com.homefix.dispatch.domain.OfferStatus;
import com.homefix.dispatch.port.JobOfferPort;
import com.homefix.dispatch.port.NotificationPort;
import com.homefix.dispatch.service.JobOfferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Default {@link JobOfferPort} adapter (Requirements 8.5-8.7). The Dispatch Engine owns the offer:
 * it records a PENDING offer through {@link JobOfferService} (Redis-backed, so any instance's
 * provider API can see and decide it), nudges the provider with a best-effort push, and then waits
 * for the provider's answer, which arrives through {@code POST /dispatch/offers/{bookingId}/accept}
 * or {@code /decline}.
 *
 * <p>Waiting is a short-interval poll of the offer's status. The dispatch design already dedicates
 * a bulkhead thread to each in-flight offer, so blocking it here costs nothing extra. When the
 * window closes the offer is expired by compare-and-set: if the provider's decision landed first
 * it is honoured, otherwise the offer becomes EXPIRED and any later accept is refused.
 *
 * <p>Failure handling is "treat it as a timeout": if the offer cannot be recorded the provider can
 * never answer it, and the dispatch loop simply moves on to the next candidate. The push runs on a
 * separate executor so a slow or missing Notification Service never eats into the window.
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
    public OfferOutcome offer(UUID bookingId, UUID providerId, Duration timeout) {
        return await(() -> offers.open(bookingId, providerId, timeout));
    }

    @Override
    public OfferOutcome offer(DispatchRequest request, UUID providerId, Duration timeout) {
        return await(() -> offers.open(request, providerId, timeout));
    }

    private OfferOutcome await(Supplier<JobOffer> open) {
        JobOffer offer;
        try {
            offer = open.get();
        } catch (RuntimeException e) {
            log.warn("Could not record a job offer; treating it as a timeout: {}", e.toString());
            return OfferOutcome.TIMED_OUT;
        }
        UUID bookingId = offer.bookingId();
        UUID providerId = offer.providerId();
        pushBestEffort(offer);

        try {
            Instant now;
            while ((now = clock.instant()).isBefore(offer.expiresAt())) {
                // null is a storage hiccup: keep waiting and let the closing compare-and-set decide.
                Optional<OfferStatus> status = readStatus(bookingId, providerId);
                if (status != null) {
                    if (status.isEmpty()) {
                        // Gone from storage, or re-offered elsewhere by an overlapping run.
                        return OfferOutcome.TIMED_OUT;
                    }
                    if (status.get().isTerminal()) {
                        return outcomeOf(status.get());
                    }
                }
                Duration remaining = Duration.between(now, offer.expiresAt());
                sleeper.sleep(remaining.compareTo(pollInterval) < 0 ? remaining : pollInterval);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted waiting on the offer of booking {} to provider {}; closing it",
                    bookingId, providerId);
        }
        return close(bookingId, providerId);
    }

    /** @return the status, empty when the offer is gone, or {@code null} when it could not be read */
    private Optional<OfferStatus> readStatus(UUID bookingId, UUID providerId) {
        try {
            return offers.statusOf(bookingId, providerId);
        } catch (RuntimeException e) {
            log.debug("Could not read the offer of booking {}: {}", bookingId, e.toString());
            return null;
        }
    }

    /** Expires the offer unless the provider decided first, and reports which happened. */
    private OfferOutcome close(UUID bookingId, UUID providerId) {
        try {
            OfferStatus last = offers.expire(bookingId, providerId);
            log.debug("Offer of booking {} to provider {} closed as {}", bookingId, providerId, last);
            return outcomeOf(last);
        } catch (RuntimeException e) {
            log.warn("Could not close the offer of booking {} to provider {}; treating it as a timeout: {}",
                    bookingId, providerId, e.toString());
            return OfferOutcome.TIMED_OUT;
        }
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
