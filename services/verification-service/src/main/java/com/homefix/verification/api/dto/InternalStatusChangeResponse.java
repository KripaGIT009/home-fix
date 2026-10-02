package com.homefix.verification.api.dto;

import java.util.UUID;

import com.homefix.verification.domain.Verification;

/**
 * Response of the internal suspend / reinstate actions: the provider's verification status after
 * the change. Deliberately narrower than {@link VerificationResponse} — the calling service needs
 * the outcome, not the document references or the background-check result.
 */
public record InternalStatusChangeResponse(UUID providerId, String status) {

    public static InternalStatusChangeResponse from(Verification v) {
        return new InternalStatusChangeResponse(v.getProviderId(), v.getStatus().name());
    }
}
