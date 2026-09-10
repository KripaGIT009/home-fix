package com.homefix.shared.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repository over the transactional outbox table (Task 6).
 *
 * <p>The Outbox Processor uses {@link #findByStatusOrderByCreatedAtAsc} to poll unpublished
 * rows in insertion order, guaranteeing events are relayed roughly in the order they were
 * produced.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Fetches a page of rows in the given status, oldest first.
     */
    @Query("select e from OutboxEventEntity e where e.status = :status order by e.createdAt asc")
    List<OutboxEventEntity> findByStatusOrderByCreatedAtAsc(@Param("status") OutboxEventStatus status,
                                                            Pageable pageable);

    long countByStatus(OutboxEventStatus status);
}
