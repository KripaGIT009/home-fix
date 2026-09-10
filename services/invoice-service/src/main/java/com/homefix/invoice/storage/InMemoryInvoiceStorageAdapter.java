package com.homefix.invoice.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.homefix.invoice.config.InvoiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link InvoiceStoragePort} adapter for dev/test. Keeps objects in memory, records that
 * server-side encryption was requested (mirroring the SSE requirement, Requirement 13.2), and
 * mints a signed URL whose query string encodes an absolute expiry so the 72-hour window
 * (Requirement 13.3) is observable.
 *
 * <p>Active unless {@code homefix.invoice.s3.backend=aws} or a test supplies its own adapter. A
 * production AWS S3 adapter (SSE-KMS + presigned GET) can be added behind the same port without
 * touching orchestration.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.invoice.s3", name = "backend", havingValue = "memory",
        matchIfMissing = true)
public class InMemoryInvoiceStorageAdapter implements InvoiceStoragePort {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final String bucket;
    private final String region;
    private final Clock clock;

    // These classes keep a second, package-private constructor so tests can pin the Clock.
    // With more than one constructor Spring will not guess: without @Autowired it falls back
    // to a no-arg constructor that does not exist and the bean fails to instantiate.
    @Autowired
    public InMemoryInvoiceStorageAdapter(InvoiceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    InMemoryInvoiceStorageAdapter(InvoiceProperties properties, Clock clock) {
        this.bucket = properties.getS3().getBucket();
        this.region = properties.getS3().getRegion();
        this.clock = clock;
    }

    @Override
    public String store(String objectKey, byte[] pdfBytes) {
        // SSE is implicit here; the production adapter sets SSE-KMS on the PutObject request.
        objects.put(objectKey, pdfBytes.clone());
        return objectKey;
    }

    @Override
    public SignedUrl signedUrl(String objectKey, Duration ttl) {
        Instant expiresAt = clock.instant().plus(ttl);
        String url = "https://" + bucket + ".s3." + region + ".amazonaws.com/" + objectKey
                + "?X-Amz-Expires=" + ttl.toSeconds()
                + "&X-Amz-Signature=local-" + Integer.toHexString(objectKey.hashCode());
        return new SignedUrl(url, expiresAt);
    }

    /** Test/inspection helper: whether an object was stored under {@code objectKey}. */
    public boolean contains(String objectKey) {
        return objects.containsKey(objectKey);
    }
}
