package com.homefix.verification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /admin/verification/{providerId}/background-check}: the result of the check
 * the admin ran and the decision it leads to (Requirement 5.6-5.8). {@code reason} is required when
 * the check failed (it is shown to the provider), optional when it passed.
 */
public record BackgroundCheckDecisionRequest(
        @NotNull(message = "outcome is required") Outcome outcome,
        @NotBlank(message = "result is required")
        @Size(max = 2000, message = "result must be at most 2000 characters") String result,
        @Size(max = 1000, message = "reason must be at most 1000 characters") String reason) {

    /** What the background check found. */
    public enum Outcome {
        /** Clear: the provider is approved for jobs. */
        PASSED,
        /** Not clear: the provider is rejected with the reason. */
        FAILED
    }
}
