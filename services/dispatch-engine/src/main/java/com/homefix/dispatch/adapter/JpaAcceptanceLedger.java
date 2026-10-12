package com.homefix.dispatch.adapter;

import com.homefix.dispatch.domain.PendingAcceptance;
import com.homefix.dispatch.domain.PendingAcceptanceRepository;
import com.homefix.dispatch.port.AcceptanceLedger;
import com.homefix.dispatch.service.ProviderAcceptedPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Default {@link AcceptanceLedger}: a {@code dispatch.pending_acceptance} row per acceptance in
 * flight, in the same database as the shared outbox, so {@link #announce} can delete the row and
 * write the {@code ProviderAccepted} outbox row in one local transaction.
 *
 * <p>Timing:
 * <ul>
 *   <li>A new entry is leased for {@link #LEASE} before the reconciler may touch it. That covers
 *       the dispatch thread's own Booking Service call, retries included (three 5 s attempts with
 *       backoff), so the two do not normally race; if they do, the Booking Service call is
 *       idempotent and {@link #announce} lets only one of them publish.</li>
 *   <li>A failed attempt is retried after 30 s, doubling per failure up to {@link #MAX_BACKOFF}.
 *       There is no give-up: the acceptance has happened, and the entry ends only when it is
 *       announced or the Booking Service definitively refuses it.</li>
 * </ul>
 */
@Component
public class JpaAcceptanceLedger implements AcceptanceLedger {

    private static final Logger log = LoggerFactory.getLogger(JpaAcceptanceLedger.class);

    /** How long a freshly opened entry belongs to the dispatch thread that opened it. */
    static final Duration LEASE = Duration.ofMinutes(2);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final PendingAcceptanceRepository repository;
    private final ProviderAcceptedPublisher acceptedPublisher;
    private final Clock clock;

    public JpaAcceptanceLedger(PendingAcceptanceRepository repository,
                               ProviderAcceptedPublisher acceptedPublisher,
                               Clock clock) {
        this.repository = repository;
        this.acceptedPublisher = acceptedPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void open(UUID bookingId, UUID customerId, UUID providerId, Instant bookingCreatedAt) {
        Instant now = clock.instant();
        repository.save(new PendingAcceptance(bookingId, customerId, providerId, bookingCreatedAt,
                now, now.plus(LEASE)));
    }

    @Override
    @Transactional
    public void markBookingAccepted(UUID bookingId) {
        repository.findById(bookingId)
                .ifPresent(entry -> entry.markBookingAccepted(clock.instant()));
    }

    /**
     * The event is written only by the call whose delete removed the row. The booking's acceptance
     * and the event therefore commit or roll back together, and a concurrent runner that finds the
     * row already gone writes nothing.
     */
    @Override
    @Transactional
    public boolean announce(UUID bookingId) {
        PendingAcceptance entry = repository.findById(bookingId).orElse(null);
        if (entry == null) {
            return false;
        }
        if (repository.deleteByBookingIdReturningCount(bookingId) == 0) {
            log.debug("Acceptance of booking {} was announced by another runner", bookingId);
            return false;
        }
        acceptedPublisher.publish(entry.getBookingId(), entry.getCustomerId(), entry.getProviderId(),
                entry.getBookingCreatedAt());
        return true;
    }

    @Override
    @Transactional
    public void discard(UUID bookingId) {
        repository.deleteByBookingIdReturningCount(bookingId);
    }

    @Override
    @Transactional
    public void postpone(UUID bookingId, String error) {
        repository.findById(bookingId).ifPresent(entry -> {
            Instant retryAt = clock.instant().plus(backoff(entry.getAttempts() + 1));
            entry.recordFailure(error, retryAt);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> due(int limit) {
        return repository
                .findByNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(clock.instant(), PageRequest.of(0, limit))
                .stream()
                .map(p -> new Entry(p.getBookingId(), p.getCustomerId(), p.getProviderId(),
                        p.getBookingCreatedAt(), p.isBookingAccepted(), p.getAttempts()))
                .toList();
    }

    /** 30 s after the first failure, doubling, capped at 10 minutes. */
    static Duration backoff(int failures) {
        int doublings = Math.max(0, Math.min(failures - 1, 10));
        Duration delay = FIRST_BACKOFF.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }
}
