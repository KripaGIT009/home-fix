package com.homefix.admin.verification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A Provider awaiting document verification, as shown in the Admin Verification Queue
 * (Requirement 19.3). Entries are always in DOCUMENT_SUBMITTED status and are returned sorted by
 * {@code submittedAt} oldest first. Each carries its submitted documents for inline viewing.
 */
public record VerificationQueueEntry(
        UUID providerId,
        Instant submittedAt,
        List<SubmittedDocument> documents) {
}
