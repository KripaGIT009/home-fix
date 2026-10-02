package com.homefix.complaint.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.complaint.domain.Complaint;

/**
 * A complaint as the Admin Portal's complaint table reads it ({@code AdminComplaint} in
 * {@code features/complaints/api.ts}, Requirement 19.2).
 *
 * <p>{@code bookingReference} and {@code raisedByName} are owned by the Booking and Auth services;
 * this service holds only their ids, so both are sent as {@code null} rather than calling across
 * services or inventing a value. {@code summary} is the customer's description and
 * {@code slaDueAt} the resolution SLA deadline (16.4). {@code status} is the backend enum name,
 * including the states the portal did not originally model (ESCALATED, REFUND_FAILED, CLOSED).
 */
public record AdminComplaintResponse(
        UUID id,
        String bookingReference,
        String raisedByName,
        String category,
        String summary,
        String status,
        Instant slaDueAt,
        Instant createdAt) {

    public static AdminComplaintResponse from(Complaint c) {
        return new AdminComplaintResponse(
                c.getId(),
                null,
                null,
                c.getCategory().name(),
                c.getDescription(),
                c.getStatus().name(),
                c.getSlaDeadline(),
                c.getCreatedAt());
    }
}
