package com.homefix.complaint.api.dto;

import java.util.List;
import java.util.UUID;

import com.homefix.complaint.domain.ComplaintCategory;
import com.homefix.complaint.domain.ServicePriority;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a complaint (Requirement 16.1). The customer is derived from the JWT
 * principal, not the body. Bean-validation enforces required fields and the 2000-character
 * description cap; per-attachment size and count are enforced in the service layer.
 */
public record CreateComplaintRequest(
        @NotNull UUID bookingId,
        UUID providerId,
        @NotNull ComplaintCategory category,
        @NotNull ServicePriority priority,
        @NotNull @Size(max = 2000) String description,
        List<AttachmentRequest> attachments) {

    /** A single evidence attachment reference (Requirement 16.1). */
    public record AttachmentRequest(String fileName, long sizeBytes) {
    }
}
