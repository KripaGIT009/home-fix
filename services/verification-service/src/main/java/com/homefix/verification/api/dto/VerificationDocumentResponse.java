package com.homefix.verification.api.dto;

import java.util.UUID;

import com.homefix.verification.domain.DocumentType;
import com.homefix.verification.domain.VerificationDocument;

/**
 * One entry of {@code GET /admin/verification/{providerId}/documents}, shaped exactly as the Admin
 * Portal's {@code VerificationDocument} (Requirement 19.3).
 *
 * <p>{@code url} is always {@code null} for now: the storage port only stores documents, and the
 * current adapter is a stand-in that computes an S3 key without uploading, so there is no object
 * to presign. Exposing the raw {@code s3://} storage reference instead would leak the bucket
 * layout without giving the browser anything it can render. {@code fileName} is {@code null}
 * because the original upload file name is not persisted.
 */
public record VerificationDocumentResponse(
        UUID id,
        String type,
        String contentType,
        String url,
        String fileName) {

    public static VerificationDocumentResponse from(VerificationDocument document) {
        return new VerificationDocumentResponse(
                document.getId(),
                label(document.getDocumentType()),
                document.getContentType(),
                null,
                null);
    }

    /** The human label the portal shows for a document type. */
    static String label(DocumentType type) {
        return switch (type) {
            case GOVERNMENT_ID -> "Government ID";
            case ADDRESS_PROOF -> "Address proof";
            case SKILL_CERTIFICATION -> "Skill certification";
        };
    }
}
