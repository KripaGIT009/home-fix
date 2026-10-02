package com.homefix.verification.api.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Response of {@code POST /internal/verifications/statuses}: the current verification status
 * (a {@code VerificationStatus} name) of each requested id that has a verification record. Ids with
 * no record are absent; the caller reads that as "has not submitted documents yet".
 */
public record VerificationStatusesResponse(Map<UUID, String> statuses) {
}
