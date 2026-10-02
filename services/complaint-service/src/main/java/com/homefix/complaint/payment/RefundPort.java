package com.homefix.complaint.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Submits refund requests to the Payment Service on behalf of a complaint (Requirement 16.5).
 * Modelled as a port so the transport (synchronous HTTP, Kafka command, etc.) can vary and so it is
 * mockable in unit tests. A rejection is surfaced via {@link RefundResult#approved()} rather than
 * an exception so the caller can drive the REFUND_FAILED flow (Requirement 16.6).
 *
 * <p>The caller invokes this port with <strong>no database transaction open</strong>, after the
 * PENDING refund record has committed. {@code idempotencyKey} is stable for that record (at most 64
 * characters), so a transport that retries, or a re-submission of the same refund, is
 * de-duplicated by the Payment Service. An HTTP adapter must forward it as the
 * {@code idempotencyKey} field of {@code POST /payments/{transactionId}/refunds}, which the Payment
 * Service requires.
 */
public interface RefundPort {

    /**
     * Requests a refund of {@code amount} for the booking associated with a complaint.
     *
     * @param idempotencyKey stable key for this refund; the Payment Service executes at most one
     *                       refund per key
     * @return the {@link RefundResult}; {@link RefundResult#approved()} is {@code false} when the
     *         Payment Service rejects the request.
     */
    RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount,
                               String idempotencyKey);
}
