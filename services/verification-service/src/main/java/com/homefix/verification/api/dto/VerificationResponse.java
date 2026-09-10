package com.homefix.verification.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.homefix.verification.domain.Verification;

/**
 * Read model for a verification record, including the audit trail (Requirement 5.11).
 */
public record VerificationResponse(
        UUID id,
        UUID providerId,
        String status,
        Instant backgroundCheckStartedAt,
        String backgroundCheckResult,
        List<DocumentSummary> documents,
        List<AuditEntryResponse> auditTrail) {

    public record DocumentSummary(String documentType, String storageRef, Instant uploadedAt) {
    }

    public record AuditEntryResponse(
            long sequence, String fromState, String toState, UUID actorId,
            String reason, Instant createdAt) {
    }

    public static VerificationResponse from(Verification v) {
        List<DocumentSummary> docs = v.getDocuments().stream()
                .map(d -> new DocumentSummary(d.getDocumentType().name(), d.getStorageRef(), d.getUploadedAt()))
                .toList();
        List<AuditEntryResponse> trail = v.getAuditTrail().stream()
                .map(a -> new AuditEntryResponse(a.getSequence(), a.getFromState().name(),
                        a.getToState().name(), a.getActorId(), a.getReason(), a.getCreatedAt()))
                .toList();
        return new VerificationResponse(v.getId(), v.getProviderId(), v.getStatus().name(),
                v.getBackgroundCheckStartedAt(), v.getBackgroundCheckResult(), docs, trail);
    }
}
