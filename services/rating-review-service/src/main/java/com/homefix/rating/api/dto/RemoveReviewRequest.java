package com.homefix.rating.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for Admin review removal (Requirement 15.9). The reason is mandatory and recorded in
 * the Audit_Log alongside the acting Admin's user ID.
 */
public record RemoveReviewRequest(
        @NotBlank @Size(max = 1000) String reason) {
}
