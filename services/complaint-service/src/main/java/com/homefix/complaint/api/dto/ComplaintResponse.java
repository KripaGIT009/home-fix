package com.homefix.complaint.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.complaint.domain.Complaint;

/** Response projection of a {@link Complaint} (Requirement 16). */
public record ComplaintResponse(
        UUID id,
        UUID bookingId,
        UUID customerId,
        UUID providerId,
        UUID agentId,
        String category,
        String priority,
        String status,
        boolean acknowledged,
        boolean settlementHeld,
        boolean escalated,
        Instant createdAt,
        Instant slaDeadline,
        Instant resolvedAt) {

    public static ComplaintResponse from(Complaint c) {
        return new ComplaintResponse(
                c.getId(),
                c.getBookingId(),
                c.getCustomerId(),
                c.getProviderId(),
                c.getAgentId(),
                c.getCategory().name(),
                c.getPriority().name(),
                c.getStatus().name(),
                c.isAcknowledged(),
                c.isSettlementHeld(),
                c.isEscalated(),
                c.getCreatedAt(),
                c.getSlaDeadline(),
                c.getResolvedAt());
    }
}
