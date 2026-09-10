package com.homefix.rating.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /reviews} (Requirement 15.3). Bean-validation covers the coarse
 * checks (1–5 range, text length, attachment count); the service performs window and fraud checks.
 */
public record SubmitReviewRequest(
        @NotNull UUID bookingId,
        @Min(1) @Max(5) int overall,
        @Min(1) @Max(5) int behavior,
        @Min(1) @Max(5) int quality,
        @Min(1) @Max(5) int timeliness,
        @Min(1) @Max(5) int pricingTransparency,
        @Size(max = 1000) String reviewText,
        @Size(max = 5) List<AttachmentDto> attachments) {

    /** A single photo attachment descriptor (Requirement 15.3). */
    public record AttachmentDto(String fileName, long sizeBytes) {
    }
}
