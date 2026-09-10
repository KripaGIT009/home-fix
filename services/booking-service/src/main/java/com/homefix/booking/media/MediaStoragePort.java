package com.homefix.booking.media;

import java.util.UUID;

/**
 * Port abstracting object storage (S3) for booking media (Requirement 7.2).
 *
 * <p>Abstracted so it can be backed by an S3 adapter (server-side encryption) in production
 * and an in-memory/local adapter in tests.
 */
public interface MediaStoragePort {

    /**
     * Stores the file and returns the storage key (e.g. the S3 object key) that will be
     * persisted on the {@code job_media} row.
     */
    String store(UUID bookingId, MediaFile file);
}
