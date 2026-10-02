package com.homefix.outbox.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.homefix.shared.outbox.OutboxEventEntity;
import com.homefix.shared.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Claims batches of due outbox rows for exactly one relay instance at a time (Requirement 22.4).
 *
 * <p>A claim is one short transaction: {@code SELECT ... FOR UPDATE SKIP LOCKED} over the due
 * PENDING rows ({@link OutboxEventRepository#claimDue}), then each selected row's
 * {@code next_attempt_at} is pushed a {@code claimLease} into the future and the transaction
 * commits. The row locks cover only that transaction, never a Kafka publish:
 * <ul>
 *   <li>while the claim transaction is open, a concurrent claimer skips the locked rows instead of
 *       waiting for them, so two claimers never receive the same row;</li>
 *   <li>once it commits, the lease keeps the rows out of every other claim until it expires, which
 *       is what covers the publish itself;</li>
 *   <li>if the claiming relay dies, its rows simply become due again when the lease runs out.</li>
 * </ul>
 * Every claim and outcome write also bumps the row's {@code @Version}, so a relay that outlives its
 * lease cannot overwrite the state written by the instance that took the row over.
 *
 * <p>Runs through {@link TransactionOperations} rather than {@code @Transactional} so the
 * transaction boundary is explicit and cannot be lost to self-invocation.
 */
public class OutboxClaimer {

    private static final Logger log = LoggerFactory.getLogger(OutboxClaimer.class);

    private final OutboxEventRepository repository;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration claimLease;

    public OutboxClaimer(OutboxEventRepository repository,
                         TransactionOperations transactions,
                         Clock clock,
                         int batchSize,
                         Duration claimLease) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1, got " + batchSize);
        }
        if (claimLease == null || claimLease.isNegative() || claimLease.isZero()) {
            throw new IllegalArgumentException("claimLease must be positive, got " + claimLease);
        }
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.claimLease = claimLease;
    }

    /**
     * Claims up to {@code batchSize} due PENDING rows, oldest first.
     *
     * @return the claimed rows, each carrying its lease deadline in
     *         {@link OutboxEventEntity#getNextAttemptAt()}; detached once this method returns
     */
    public List<OutboxEventEntity> claimBatch() {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(claimLease);
        List<OutboxEventEntity> claimed = transactions.execute(status -> {
            List<OutboxEventEntity> due = repository.claimDue(now, PageRequest.of(0, batchSize));
            due.forEach(event -> event.scheduleNextAttempt(leaseUntil));
            return repository.saveAll(due);
        });
        if (claimed == null || claimed.isEmpty()) {
            return List.of();
        }
        log.debug("Claimed {} outbox events until {}", claimed.size(), leaseUntil);
        return claimed;
    }

    /**
     * Gives claimed rows back without counting an attempt against them, making them due
     * immediately. Used for the rest of a batch the relay stopped working through. A row whose
     * claim was already lost to another instance is left alone.
     */
    public void release(List<OutboxEventEntity> events) {
        Instant now = clock.instant();
        for (OutboxEventEntity event : events) {
            event.scheduleNextAttempt(now);
            try {
                repository.save(event);
            } catch (OptimisticLockingFailureException claimLost) {
                log.debug("Outbox event {} was re-claimed by another relay before it could be released",
                        event.getId());
            }
        }
    }
}
