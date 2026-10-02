package com.homefix.verification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the Admin review queue (Requirement 19.3): a verification awaiting document review,
 * read as a projection of the root row plus an aggregate over its documents, so listing the queue
 * never loads an aggregate or its collections.
 *
 * @param providerId     the provider awaiting review
 * @param documentCount  how many documents are on file
 * @param lastUploadedAt the most recent document upload, or {@code null} when none is on file
 * @param updatedAt      the verification's last change, the fallback submission time
 */
public record VerificationQueueRow(UUID providerId, Long documentCount, Instant lastUploadedAt,
                                   Instant updatedAt) {

    /**
     * When the submission under review was made: the latest document upload, falling back to the
     * record's last change (the {@code DOCUMENT_SUBMITTED} transition) when no document row exists.
     */
    public Instant submittedAt() {
        return lastUploadedAt != null ? lastUploadedAt : updatedAt;
    }
}
