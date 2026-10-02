package com.homefix.complaint.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link RefundPort} that logs and approves refund requests; it moves no money. A
 * production adapter would call the Payment Service refund endpoint (Task 18) over HTTP behind this
 * same port, forwarding {@code idempotencyKey}. Tests supply their own doubles, including ones that
 * reject to exercise the REFUND_FAILED flow (Requirement 16.6).
 *
 * <p>Mirrors the Payment Service's idempotency: the stub reference is derived from the key, so the
 * same key always yields the same reference.
 */
@Component
public class LoggingRefundAdapter implements RefundPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingRefundAdapter.class);

    @Override
    public RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount,
                                      String idempotencyKey) {
        log.info("REFUND complaint={} key={} requesting refund from Payment Service",
                complaintId, idempotencyKey);
        String reference = UUID.nameUUIDFromBytes(
                String.valueOf(idempotencyKey).getBytes(StandardCharsets.UTF_8)).toString();
        return RefundResult.approved("stub_rf_" + reference);
    }
}
