package com.homefix.booking.media;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link MediaStoragePort} for local/dev environments. It does not persist bytes; it
 * simply derives and returns a deterministic object key of the same shape the S3 adapter
 * would use ({@code bookings/{bookingId}/media/{uuid}}).
 *
 * <p>Selecting {@code homefix.media-storage.provider=s3} (a future adapter) replaces this
 * with the real SSE-enabled S3 upload without touching the booking business logic.
 */
@Component
@ConditionalOnProperty(name = "homefix.media-storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalMediaStorageAdapter implements MediaStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaStorageAdapter.class);

    @Override
    public String store(UUID bookingId, MediaFile file) {
        String key = "bookings/" + bookingId + "/media/" + UUID.randomUUID();
        log.debug("Local media storage: stored {} bytes contentType={} at key={}",
                file.sizeBytes(), file.contentType(), key);
        return key;
    }
}
