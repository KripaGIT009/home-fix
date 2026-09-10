package com.homefix.booking.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Reference to a media file attached to a booking and stored in object storage (design
 * {@code JOB_MEDIA}, Requirement 7.2).
 *
 * <p>Only the S3 object key and metadata are stored here; the bytes live in S3 behind the
 * {@code MediaStoragePort}.
 */
@Entity
@Table(name = "job_media", indexes = {
        @Index(name = "idx_job_media_booking", columnList = "booking_id")
})
public class JobMedia {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    /** Logical media role, e.g. CUSTOMER_UPLOAD, BEFORE_PHOTO, AFTER_PHOTO. */
    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "s3_key", nullable = false, length = 1024)
    private String s3Key;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected JobMedia() {
        // JPA
    }

    private JobMedia(UUID bookingId, String type, String contentType, long sizeBytes, String s3Key) {
        this.id = UUID.randomUUID();
        this.bookingId = bookingId;
        this.type = type;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.s3Key = s3Key;
        this.uploadedAt = Instant.now();
    }

    public static JobMedia of(UUID bookingId, String type, String contentType, long sizeBytes, String s3Key) {
        return new JobMedia(bookingId, type, contentType, sizeBytes, s3Key);
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public String getType() {
        return type;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
