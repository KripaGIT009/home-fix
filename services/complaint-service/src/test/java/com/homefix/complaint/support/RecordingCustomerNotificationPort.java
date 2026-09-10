package com.homefix.complaint.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.homefix.complaint.domain.ComplaintStatus;
import com.homefix.complaint.notification.CustomerNotificationPort;

/**
 * Recording {@link CustomerNotificationPort} test double: captures each customer-facing
 * notification so the acknowledgment (16.2), status-change (16.3), delay (16.4), and refund-failure
 * (16.6) notifications can be asserted.
 */
public class RecordingCustomerNotificationPort implements CustomerNotificationPort {

    private final List<UUID> acknowledgments = new ArrayList<>();
    private final List<ComplaintStatus> statusChanges = new ArrayList<>();
    private final List<UUID> delays = new ArrayList<>();
    private final List<UUID> refundFailures = new ArrayList<>();

    @Override
    public void acknowledgeComplaint(UUID customerId, UUID complaintId) {
        acknowledgments.add(complaintId);
    }

    @Override
    public void notifyStatusChange(UUID customerId, UUID complaintId, ComplaintStatus newStatus) {
        statusChanges.add(newStatus);
    }

    @Override
    public void notifyResolutionDelayed(UUID customerId, UUID complaintId) {
        delays.add(complaintId);
    }

    @Override
    public void notifyRefundFailed(UUID customerId, UUID complaintId) {
        refundFailures.add(complaintId);
    }

    public List<UUID> acknowledgments() {
        return acknowledgments;
    }

    public List<ComplaintStatus> statusChanges() {
        return statusChanges;
    }

    public List<UUID> delays() {
        return delays;
    }

    public List<UUID> refundFailures() {
        return refundFailures;
    }
}
