package com.homefix.verification.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Common request body for Admin verification actions carrying an optional (or, for rejection,
 * required) reason recorded in the audit trail (Requirement 5.11).
 */
public record AdminActionRequest(
        @Size(max = 1000, message = "reason must be at most 1000 characters") String reason) {
}
