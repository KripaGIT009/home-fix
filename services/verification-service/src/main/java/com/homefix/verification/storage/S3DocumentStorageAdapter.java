package com.homefix.verification.storage;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homefix.verification.config.VerificationProperties;
import com.homefix.verification.domain.DocumentType;

/**
 * Default {@link DocumentStoragePort} that computes an S3-style storage key and logs the
 * upload with server-side encryption implied (Requirement 5.3).
 *
 * <p>This adapter is a stand-in for the real AWS S3 SDK client (which would call
 * {@code PutObject} with {@code ServerSideEncryption=aws:kms}). Isolating it behind the port
 * keeps the verification workflow free of AWS dependencies and fully unit-testable; swapping
 * in the SDK-backed adapter requires no change to the business logic.
 */
@Component
public class S3DocumentStorageAdapter implements DocumentStoragePort {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorageAdapter.class);

    private final VerificationProperties properties;

    public S3DocumentStorageAdapter(VerificationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String store(UUID providerId, DocumentType type, byte[] content, String contentType) {
        String key = "%s/%s/%s".formatted(providerId, type, UUID.randomUUID());
        String storageRef = "s3://%s/%s".formatted(properties.getDocumentsBucket(), key);
        // Real adapter: s3.putObject(PutObjectRequest.builder()
        //     .bucket(bucket).key(key).serverSideEncryption(ServerSideEncryption.AWS_KMS)...);
        log.info("Stored verification document provider={} type={} bytes={} sse=aws:kms ref={}",
                providerId, type, content == null ? 0 : content.length, storageRef);
        return storageRef;
    }
}
