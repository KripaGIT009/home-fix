package com.homefix.verification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One provider waiting on the background-check step of verification (Requirement 5.5, 5.6): the
 * check was started when an admin accepted the documents ({@code BACKGROUND_CHECK_PENDING}), or its
 * result is recorded but no decision taken yet ({@code BACKGROUND_CHECK_COMPLETED}).
 *
 * @param startedAt when the check was started; the queue is oldest first
 * @param result    the recorded result, or null while none is
 */
public record BackgroundCheckQueueRow(UUID providerId, VerificationStatus status, Instant startedAt,
                                      String result, Long documentCount) {
}
