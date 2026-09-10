package com.homefix.verification.storage;

import com.homefix.verification.domain.DocumentType;

/**
 * Abstraction over object storage for verification documents (Requirement 5.3). Documents are
 * stored with server-side encryption (SSE). Modelled as a port so the concrete backend (AWS
 * S3, MinIO, etc.) can vary without touching the verification workflow, and so uploads are
 * trivially mockable in unit tests.
 */
public interface DocumentStoragePort {

    /**
     * Uploads a document's bytes to object storage with server-side encryption enabled and
     * returns an opaque storage reference (e.g. {@code s3://bucket/providerId/type/uuid}).
     *
     * @param providerId  the owning provider
     * @param type        the document type
     * @param content     the raw file bytes
     * @param contentType the MIME type of the file
     * @return an opaque storage reference that can be persisted and later resolved to the file
     */
    String store(java.util.UUID providerId, DocumentType type, byte[] content, String contentType);
}
