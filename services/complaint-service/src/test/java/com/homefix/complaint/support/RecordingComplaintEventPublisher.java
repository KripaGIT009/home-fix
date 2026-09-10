package com.homefix.complaint.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.homefix.complaint.domain.Complaint;
import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.event.ComplaintEventPublisher;

/**
 * Test double for {@link ComplaintEventPublisher} that records published events instead of writing
 * an outbox row, so {@code ComplaintService} can be tested without a database or an active
 * transaction. Overrides both publish methods and never invokes the outbox-backed super logic.
 */
public class RecordingComplaintEventPublisher extends ComplaintEventPublisher {

    private final List<Complaint> created = new ArrayList<>();
    private final List<StatusChange> statusChanges = new ArrayList<>();

    public RecordingComplaintEventPublisher() {
        // No outbox publisher needed: publish methods are fully overridden below.
        super(null);
    }

    @Override
    public void publishCreated(Complaint complaint) {
        created.add(complaint);
    }

    @Override
    public void publishStatusChanged(Complaint complaint, ComplaintStatus previousStatus,
                                     Instant changedAt) {
        statusChanges.add(new StatusChange(complaint, previousStatus, complaint.getStatus()));
    }

    public List<Complaint> created() {
        return created;
    }

    public List<StatusChange> statusChanges() {
        return statusChanges;
    }

    /** A single recorded status transition. */
    public record StatusChange(Complaint complaint, ComplaintStatus from, ComplaintStatus to) {
    }
}
