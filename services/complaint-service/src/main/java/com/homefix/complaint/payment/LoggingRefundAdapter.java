package com.homefix.complaint.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link RefundPort} that logs and approves refund requests. A production adapter would
 * call the Payment Service refund endpoint (Task 18) over HTTP behind this same port. Activated
 * only when no other {@link RefundPort} bean is present (tests supply their own, including doubles
 * that reject to exercise the REFUND_FAILED flow — Requirement 16.6).
 */
@Component
public class LoggingRefundAdapter implements RefundPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingRefundAdapter.class);

    @Override
    public RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount) {
        log.info("REFUND complaint={} requesting refund from Payment Service", complaintId);
        return RefundResult.approved("stub_rf_" + UUID.randomUUID());
    }
}
