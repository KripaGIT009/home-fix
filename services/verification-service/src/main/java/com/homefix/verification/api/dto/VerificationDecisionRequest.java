package com.homefix.verification.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /admin/verification/{providerId}/decision}, the Admin Portal's
 * {@code DecisionPayload} (Requirement 19.3). {@code reason} is optional when approving and
 * required when rejecting; the rejection rule is enforced by {@code VerificationService.reject}
 * so it holds for every caller, not only this endpoint.
 */
public record VerificationDecisionRequest(
        @NotNull(message = "decision is required") Decision decision,
        @Size(max = 1000, message = "reason must be at most 1000 characters") String reason) {

    /** The two outcomes of a document review. */
    public enum Decision {
        /** Documents accepted: {@code DOCUMENT_SUBMITTED → DOCUMENT_VERIFIED} (Requirement 5.4). */
        APPROVE,
        /** Documents refused: {@code DOCUMENT_SUBMITTED → REJECTED} (Requirement 5.8). */
        REJECT
    }
}
