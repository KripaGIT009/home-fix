package com.homefix.rating.api.dto;

import com.homefix.rating.service.ModerationAction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /admin/reviews/{id}/moderate} ({@code ModeratePayload} in the Admin Portal,
 * Requirement 19.2). {@code reason} is required for REMOVE; {@code ReviewService.removeReview}
 * enforces that and records it in the Audit_Log (15.9).
 */
public record ModerateReviewRequest(
        @NotNull ModerationAction action,
        @Size(max = 1000) String reason) {
}
