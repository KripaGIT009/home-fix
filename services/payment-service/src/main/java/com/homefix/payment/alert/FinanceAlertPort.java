package com.homefix.payment.alert;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emits alerts to the Finance_Admin team for cases requiring manual intervention (Requirements
 * 12.7, 12.11, 14.4). Modelled as a port so the transport (Kafka via the outbox, notification
 * service, pager, etc.) can vary without touching payment logic, and so it is mockable in tests.
 */
public interface FinanceAlertPort {

    /** A gateway refund call failed and needs manual processing (Requirement 12.7). */
    void refundFailed(UUID paymentId, UUID bookingId, BigDecimal amount, String reason);

    /** Wallet credit retries were exhausted; manual reconciliation needed (Requirement 12.11). */
    void walletCreditFailed(UUID providerId, UUID bookingId, BigDecimal amount, String reason);

    /** A settlement bank transfer failed and needs manual review (Requirement 14.4). */
    void settlementFailed(UUID providerId, UUID settlementId, BigDecimal amount, String reason);

    /**
     * The gateway reported a successful capture on a payment attempt that was already FAILED: the
     * customer's money was taken but the attempt cannot be recorded as paid. Finance_Admin must
     * refund it (or reconcile it against the booking) by hand (Requirement 12.5, 12.7).
     */
    void lateCapture(UUID paymentId, UUID bookingId, BigDecimal amount, String reason);
}
