package com.homefix.complaint.support;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataIntegrityViolationException;

import com.homefix.complaint.domain.ComplaintRefund;
import com.homefix.complaint.domain.ComplaintRefundRepository;

/**
 * In-memory {@link ComplaintRefundRepository} for unit tests. Enforces the unique
 * {@code complaint_id} constraint the real table carries, throwing
 * {@link DataIntegrityViolationException} on a second refund for the same complaint.
 */
public class InMemoryComplaintRefundRepository implements ComplaintRefundRepository {

    private final Map<UUID, ComplaintRefund> byId = new ConcurrentHashMap<>();

    @Override
    public ComplaintRefund save(ComplaintRefund refund) {
        boolean duplicate = byId.values().stream()
                .anyMatch(r -> r.getComplaintId().equals(refund.getComplaintId())
                        && !r.getId().equals(refund.getId()));
        if (duplicate) {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uk_complaint_refund_complaint\"");
        }
        byId.put(refund.getId(), refund);
        return refund;
    }

    @Override
    public Optional<ComplaintRefund> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ComplaintRefund> findByComplaintId(UUID complaintId) {
        return byId.values().stream()
                .filter(r -> r.getComplaintId().equals(complaintId))
                .findFirst();
    }

    public int count() {
        return byId.size();
    }
}
