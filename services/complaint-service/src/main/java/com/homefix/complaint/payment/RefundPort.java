package com.homefix.complaint.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Submits refund requests to the Payment Service on behalf of a complaint (Requirement 16.5).
 * Modelled as a port so the transport (synchronous HTTP, Kafka command, etc.) can vary and so it is
 * mockable in unit tests. A rejection is surfaced via {@link RefundResult#approved()} rather than
 * an exception so the caller can drive the REFUND_FAILED flow (Requirement 16.6).
 */
public interface RefundPort {

    /**
     * Requests a refund of {@code amount} for the booking associated with a complaint.
     *
     * @return the {@link RefundResult}; {@link RefundResult#approved()} is {@code false} when the
     *         Payment Service rejects the request.
     */
    RefundResult requestRefund(UUID bookingId, UUID complaintId, BigDecimal amount);
}
