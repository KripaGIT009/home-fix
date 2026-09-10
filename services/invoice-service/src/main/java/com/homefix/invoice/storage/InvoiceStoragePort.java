package com.homefix.invoice.storage;

import java.time.Duration;

/**
 * Outbound port for durable invoice-PDF storage (Requirement 13.2) and signed-URL issuance
 * (Requirement 13.3). Implementations MUST persist objects with server-side encryption enabled.
 *
 * <p>Hiding S3 behind a port keeps the orchestration flow testable without AWS and lets a future
 * AWS adapter drop in without touching business logic.
 */
public interface InvoiceStoragePort {

    /**
     * Stores {@code pdfBytes} under {@code objectKey} with server-side encryption enabled
     * (Requirement 13.2).
     *
     * @return the object key that was written (echoing {@code objectKey}).
     */
    String store(String objectKey, byte[] pdfBytes);

    /**
     * Issues a signed URL granting temporary read access to a stored object.
     *
     * @param objectKey the previously-stored object key
     * @param ttl       the validity window (72 h per Requirement 13.3)
     * @return the signed URL and its absolute expiry instant
     */
    SignedUrl signedUrl(String objectKey, Duration ttl);
}
