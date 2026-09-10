package com.homefix.verification.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A single uploaded verification document, stored in object storage with server-side
 * encryption (Requirement 5.3). Only the storage reference (bucket/key) and metadata are
 * persisted here; the file bytes live in S3.
 */
@Entity
@Table(name = "verification_document")
public class VerificationDocument {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "verification_id", nullable = false)
    private Verification verification;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private DocumentType documentType;

    /** Opaque object-storage key returned by the storage port (e.g. {@code s3://bucket/key}). */
    @Column(name = "storage_ref", nullable = false, length = 512)
    private String storageRef;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected VerificationDocument() {
        // JPA
    }

    VerificationDocument(Verification verification, DocumentType documentType,
                         String storageRef, String contentType, long sizeBytes) {
        this.id = UUID.randomUUID();
        this.verification = verification;
        this.documentType = documentType;
        this.storageRef = storageRef;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getStorageRef() {
        return storageRef;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
