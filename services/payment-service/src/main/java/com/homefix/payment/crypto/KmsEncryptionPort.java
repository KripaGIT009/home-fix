package com.homefix.payment.crypto;

/**
 * Abstraction over the platform key-management service used to encrypt stored payment credentials
 * at rest (Requirement 12.9). Raw card numbers are never stored; any token a gateway returns is
 * persisted only as ciphertext produced by this port.
 *
 * <p>Modelling encryption as a port keeps the business logic independent of the concrete key
 * provider: the default {@link LocalAesKmsAdapter} performs local AES-GCM for dev/test, while a
 * production deployment can supply an AWS-KMS-backed adapter without changing any caller. The port
 * is trivially mockable in unit tests.
 */
public interface KmsEncryptionPort {

    /**
     * Encrypts UTF-8 plaintext and returns an opaque, self-describing ciphertext token safe to
     * persist. Returns {@code null} for {@code null} input.
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a token previously produced by {@link #encrypt(String)}. Returns {@code null} for
     * {@code null} input.
     *
     * @throws EncryptionException if the ciphertext is malformed or cannot be decrypted.
     */
    String decrypt(String ciphertext);
}
