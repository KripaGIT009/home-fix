package com.homefix.verification.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.homefix.verification.domain.BackgroundCheckQueueRow;
import com.homefix.verification.provider.ProviderDirectoryPort.ProviderSummary;

/**
 * One entry of {@code GET /admin/verification/background-checks}: a provider whose documents were
 * accepted and whose background check awaits the admin's result and decision. Name and skill come
 * from the Provider Service and are null when it cannot answer.
 *
 * @param status    {@code BACKGROUND_CHECK_PENDING}, or {@code BACKGROUND_CHECK_COMPLETED} when a
 *                  result is recorded but not decided
 * @param startedAt when the check was started (the documents were accepted)
 * @param result    the recorded result, or null
 */
public record BackgroundCheckQueueEntryResponse(
        UUID providerId,
        String displayName,
        String primarySkill,
        String status,
        Instant startedAt,
        String result,
        long documentCount) {

    public static BackgroundCheckQueueEntryResponse from(BackgroundCheckQueueRow row, ProviderSummary summary) {
        return new BackgroundCheckQueueEntryResponse(
                row.providerId(),
                summary == null ? null : summary.displayName(),
                summary == null ? null : summary.primarySkill(),
                row.status().name(),
                row.startedAt(),
                row.result(),
                row.documentCount() == null ? 0 : row.documentCount());
    }
}
