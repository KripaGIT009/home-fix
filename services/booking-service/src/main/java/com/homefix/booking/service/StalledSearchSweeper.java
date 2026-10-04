package com.homefix.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.homefix.booking.config.BookingProperties;
import com.homefix.booking.domain.Booking;
import com.homefix.booking.domain.BookingRepository;
import com.homefix.booking.domain.BookingStatus;

/**
 * Rescues bookings whose provider search was lost (review 17.5 item 4). The Dispatch Engine
 * acknowledges {@code BookingCreated} before it searches, so an outage or restart mid-search left
 * the booking in SEARCHING_PROVIDER for good: nobody would ever report that the search ended, the
 * customer was never told, and the booking never reached the Tenant fallback.
 *
 * <p>Every minute, each booking that entered SEARCHING_PROVIDER longer than
 * {@code homefix.booking.provider-search-timeout} ago (default 60 minutes, well beyond the longest
 * legitimate search) is settled through {@link DispatchOutcomeService#expireStalledSearch}, exactly
 * as if the Dispatch Engine had reported that nobody accepted: the covering Tenants' queue, or
 * SEARCHING_FAILED with the customer told through the state-driven {@code BookingCancelled}, which
 * also tells a still-running search to stop. Requeuing the search instead would need a new
 * dispatch request event and could repeat the same loss, so it is not attempted.
 *
 * <p>The outcome service does its HTTP lookups outside any transaction and applies the result in a
 * short one that re-reads the booking; a booking accepted or cancelled since it was listed is
 * refused there as an illegal transition, and a concurrent change as an optimistic-lock failure.
 * Both are expected and skipped, and a failure on one booking never stops the batch. A dispatch
 * acceptance that arrives after the sweep is refused (the booking is no longer SEARCHING_PROVIDER).
 */
@Component
public class StalledSearchSweeper {

    private static final Logger log = LoggerFactory.getLogger(StalledSearchSweeper.class);

    /** Most bookings settled per pass; a backlog drains over successive minutes. */
    static final int BATCH_SIZE = 100;

    private final BookingRepository bookingRepository;
    private final DispatchOutcomeService dispatchOutcomes;
    private final BookingProperties properties;
    private final Clock clock;

    public StalledSearchSweeper(BookingRepository bookingRepository,
                                DispatchOutcomeService dispatchOutcomes,
                                BookingProperties properties,
                                Clock clock) {
        this.bookingRepository = bookingRepository;
        this.dispatchOutcomes = dispatchOutcomes;
        this.properties = properties;
        this.clock = clock;
    }

    /** The scheduled pass: once a minute, first run a minute after start-up. */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public void sweepScheduled() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Stalled search sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Settles every booking whose search has run past the timeout (up to {@value #BATCH_SIZE}).
     *
     * @return how many bookings this call moved out of SEARCHING_PROVIDER
     */
    public int sweep() {
        Duration timeout = properties.getProviderSearchTimeout();
        Instant cutoff = Instant.now(clock).minus(timeout);
        List<UUID> stalled = bookingRepository
                .findSearchingProviderSince(cutoff, PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(Booking::getId)
                .toList();
        int settled = 0;
        for (UUID bookingId : stalled) {
            try {
                Booking result = dispatchOutcomes.expireStalledSearch(bookingId, timeout);
                if (result != null && result.getStatus() != BookingStatus.SEARCHING_PROVIDER) {
                    settled++;
                    log.warn("Booking {} was still searching after {}; settled as {}",
                            bookingId, timeout, result.getStatus());
                }
            } catch (InvalidTransitionException | OptimisticLockingFailureException e) {
                log.debug("Booking {} left SEARCHING_PROVIDER while its stalled search was being "
                        + "settled; skipping", bookingId);
            } catch (RuntimeException e) {
                log.warn("Could not settle the stalled search of booking {}: {}", bookingId, e.getMessage());
            }
        }
        if (settled > 0) {
            log.info("Stalled search: {} booking(s) settled", settled);
        }
        return settled;
    }
}
