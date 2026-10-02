package com.homefix.complaint.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Persistence for {@link Complaint}. Beyond CRUD, exposes the queries backing the SLA-breach
 * escalation sweep (Requirement 16.4) and complaint statistics aggregation (Requirement 16.9).
 */
public interface ComplaintRepository extends JpaRepository<Complaint, UUID> {

    /**
     * Non-terminal, not-yet-escalated complaints whose SLA deadline is on/before {@code asOf}.
     * Backs the periodic escalation sweep (Requirement 16.4).
     */
    List<Complaint> findByStatusNotInAndEscalatedFalseAndSlaDeadlineLessThanEqual(
            List<ComplaintStatus> statuses, Instant asOf);

    /**
     * Loads a complaint holding a row lock until the surrounding transaction ends. Serialises
     * concurrent refund approvals of the same complaint (Requirement 16.5).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Complaint c where c.id = :id")
    Optional<Complaint> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The Admin Portal complaint list (Requirement 19.2): complaints in any of {@code statuses}
     * whose description, or complaint, booking or customer id, contains {@code pattern} (a
     * lower-case {@code LIKE} pattern with {@code \} escaping literal wildcards; {@code "%"}
     * matches everything), newest first, bounded by {@code page}. The ids are UUIDs, so the
     * cast-and-lower comparison is how a partial id typed into the portal's search box finds its
     * complaint.
     */
    @Query("""
            select c from Complaint c
            where c.status in :statuses
              and (lower(c.description) like :pattern escape '\\'
                   or lower(cast(c.bookingId as string)) like :pattern escape '\\'
                   or lower(cast(c.customerId as string)) like :pattern escape '\\'
                   or lower(cast(c.id as string)) like :pattern escape '\\')
            order by c.createdAt desc
            """)
    List<Complaint> searchForAdmin(@Param("statuses") Collection<ComplaintStatus> statuses,
                                   @Param("pattern") String pattern,
                                   Pageable page);

    /** All complaints, used to aggregate category counts and resolution stats (Requirement 16.9). */
    @Override
    List<Complaint> findAll();
}
