package com.homefix.complaint.notification;

import java.util.UUID;

import com.homefix.complaint.domain.ComplaintStatus;

/**
 * Delivers customer-facing notifications for a complaint via the Notification Service
 * (Requirement 16.2, 16.3, 16.4, 16.6). Modelled as a port so the transport (synchronous HTTP,
 * Kafka event, etc.) can vary and so it is mockable in unit tests.
 */
public interface CustomerNotificationPort {

    /** Acknowledges receipt of a newly-created complaint to the customer (Requirement 16.2). */
    void acknowledgeComplaint(UUID customerId, UUID complaintId);

    /** Notifies the customer of a complaint status change via in-app notification (16.3). */
    void notifyStatusChange(UUID customerId, UUID complaintId, ComplaintStatus newStatus);

    /** Notifies the customer that resolution is delayed after an SLA breach (16.4). */
    void notifyResolutionDelayed(UUID customerId, UUID complaintId);

    /** Notifies the customer that their refund could not be processed automatically (16.6). */
    void notifyRefundFailed(UUID customerId, UUID complaintId);
}
