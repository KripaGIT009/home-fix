package com.homefix.complaint.alert;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emits internal alerts to the Finance_Admin team (Requirement 16.6). Modelled as a port so the
 * alerting transport (Kafka via the outbox, notification service, etc.) can vary without touching
 * the complaint business logic, and so it is mockable in unit tests.
 */
public interface FinanceAlertPort {

    /**
     * Alerts Finance_Admin that an automated refund for a complaint was rejected by the Payment
     * Service and requires manual processing (Requirement 16.6).
     */
    void refundRequiresManualProcessing(UUID complaintId, UUID bookingId, BigDecimal amount,
                                        String reason);
}
