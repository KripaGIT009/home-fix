package com.homefix.complaint.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Persistence for {@link ComplaintRefund} (Requirement 16.5, 16.6). Deliberately a narrow
 * {@link Repository} exposing only what the refund flow needs. The unique {@code complaint_id}
 * column is the authoritative guard against a complaint being refunded twice.
 */
public interface ComplaintRefundRepository extends Repository<ComplaintRefund, UUID> {

    ComplaintRefund save(ComplaintRefund refund);

    Optional<ComplaintRefund> findById(UUID id);

    /** @return the complaint's refund, if one was ever requested (at most one exists). */
    Optional<ComplaintRefund> findByComplaintId(UUID complaintId);
}
