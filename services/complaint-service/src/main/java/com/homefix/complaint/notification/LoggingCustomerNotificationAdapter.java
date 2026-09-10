package com.homefix.complaint.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.complaint.domain.ComplaintStatus;

/**
 * Default {@link CustomerNotificationPort} that logs a structured request to notify the customer.
 * A production adapter would enqueue a Notification Service dispatch (via HTTP or the shared
 * outbox) without touching the complaint logic. Activated only when no other
 * {@link CustomerNotificationPort} bean is present (tests supply their own).
 */
@Component
public class LoggingCustomerNotificationAdapter implements CustomerNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingCustomerNotificationAdapter.class);

    @Override
    public void acknowledgeComplaint(UUID customerId, UUID complaintId) {
        log.info("NOTIFY complaint={} acknowledge customer of receipt", complaintId);
    }

    @Override
    public void notifyStatusChange(UUID customerId, UUID complaintId, ComplaintStatus newStatus) {
        log.info("NOTIFY complaint={} status changed to {}", complaintId, newStatus);
    }

    @Override
    public void notifyResolutionDelayed(UUID customerId, UUID complaintId) {
        log.info("NOTIFY complaint={} resolution delayed (SLA breached)", complaintId);
    }

    @Override
    public void notifyRefundFailed(UUID customerId, UUID complaintId) {
        log.info("NOTIFY complaint={} refund could not be processed automatically", complaintId);
    }
}
