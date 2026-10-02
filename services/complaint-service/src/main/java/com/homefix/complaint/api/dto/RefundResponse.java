package com.homefix.complaint.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.homefix.complaint.domain.ComplaintRefund;

/**
 * The recorded refund of a complaint (Requirement 16.5, 16.6). {@code status} is SUCCEEDED with the
 * Payment Service {@code externalReference}, or FAILED with a {@code failureReason}.
 */
public record RefundResponse(
        UUID id,
        UUID complaintId,
        UUID bookingId,
        BigDecimal amount,
        String reason,
        UUID approvedBy,
        String status,
        String externalReference,
        String failureReason,
        Instant requestedAt,
        Instant completedAt) {

    public static RefundResponse from(ComplaintRefund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getComplaintId(),
                refund.getBookingId(),
                refund.getAmount(),
                refund.getReason(),
                refund.getApprovedBy(),
                refund.getStatus().name(),
                refund.getExternalReference(),
                refund.getFailureReason(),
                refund.getRequestedAt(),
                refund.getCompletedAt());
    }
}
