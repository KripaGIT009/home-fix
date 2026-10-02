package com.homefix.verification.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.verification.domain.VerificationQueueRow;
import com.homefix.verification.provider.ProviderDirectoryPort.ProviderSummary;

/**
 * One entry of {@code GET /admin/verification/queue}, shaped exactly as the Admin Portal's
 * {@code VerificationQueueEntry} (Requirement 19.3).
 *
 * <p>{@code displayName} and {@code primarySkill} are owned by the Provider Service and borrowed
 * through {@code ProviderDirectoryPort}; they are {@code null} when that lookup is unavailable or
 * the provider has no profile. {@code mobileNumber} is owned by the Auth Service, which this
 * service does not call, so it is always {@code null}.
 */
public record VerificationQueueEntryResponse(
        UUID providerId,
        String displayName,
        String mobileNumber,
        String primarySkill,
        Instant submittedAt,
        long documentCount) {

    public static VerificationQueueEntryResponse from(VerificationQueueRow row, ProviderSummary summary) {
        return new VerificationQueueEntryResponse(
                row.providerId(),
                summary == null ? null : summary.displayName(),
                null,
                summary == null ? null : summary.primarySkill(),
                row.submittedAt(),
                row.documentCount() == null ? 0 : row.documentCount());
    }
}
