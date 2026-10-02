package com.homefix.shared.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository over the transactional outbox table (Task 6).
 *
 * <p>The Outbox Processor claims work with {@link #claimDue}, which returns due PENDING rows in
 * insertion order, so events are relayed roughly in the order they were produced.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Lock-timeout hint value meaning "skip rows another transaction has locked"
     * ({@code org.hibernate.LockOptions.SKIP_LOCKED}). With {@link LockModeType#PESSIMISTIC_WRITE}
     * Hibernate renders it as {@code FOR UPDATE SKIP LOCKED} on PostgreSQL.
     */
    String SKIP_LOCKED = "-2";

    /**
     * Fetches a page of rows in the given status, oldest first. Takes no locks; for reporting and
     * tests. The relay must use {@link #claimDue} instead.
     */
    @Query("select e from OutboxEventEntity e where e.status = :status order by e.createdAt asc")
    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(@Param("status") OutboxEventStatus status,
                                                            Pageable pageable);

    /**
     * Selects up to a page of PENDING rows that are due at {@code now} (no claim lease or retry
     * deadline in the future), oldest first, and row-locks them with
     * {@code SELECT ... FOR UPDATE SKIP LOCKED} (Requirement 22.4).
     *
     * <p>Must run inside a transaction, and the caller must push each returned row's
     * {@code next_attempt_at} forward (its claim lease) before committing. Rows another relay
     * instance has locked in a concurrent claim are skipped rather than waited for, so concurrent
     * claimers receive disjoint sets; once the claim commits, the lease keeps the row out of every
     * other claim until it expires.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query("select e from OutboxEventEntity e"
            + " where e.status = com.homefix.shared.outbox.OutboxEventStatus.PENDING"
            + " and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)"
            + " order by e.createdAt asc")
    List<OutboxEventEntity> claimDue(@Param("now") Instant now, Pageable pageable);

    long countByStatus(OutboxEventStatus status);
}
