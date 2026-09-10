package com.homefix.complaint.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

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

    /** All complaints, used to aggregate category counts and resolution stats (Requirement 16.9). */
    @Override
    List<Complaint> findAll();
}
