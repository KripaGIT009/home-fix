package com.homefix.complaint.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.complaint.domain.ComplaintStatus;

/**
 * Default {@link CustomerNotificationPort}: logs a structured record of each customer notification
 * and sends nothing. Keep the acknowledgment and status-change calls log-only: the Notification
 * Service consumes {@code ComplaintCreated} and {@code ComplaintStatusChanged} from the outbox and
 * notifies the customer itself (resolving contact details from the events' {@code customerId}), so
 * dispatching here as well would notify the customer twice (Requirement 16.2, 16.3). Tests supply
 * their own recording double.
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
