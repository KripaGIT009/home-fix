package com.homefix.customer.crypto;

/**
 * Hexagonal port abstracting field-level PII encryption backed by a cloud KMS
 * (Requirement 2.7, 26.3).
 *
 * <p>The profile/address logic depends only on this interface, never on the AWS KMS SDK.
 * Adding a new key backend (AWS KMS, GCP KMS, Vault, ...) requires only a new adapter — no
 * change to business logic — and tests can substitute a deterministic in-memory
 * implementation. Ciphertext is AES-256; keys are managed by the KMS.
 */
public interface KmsEncryptionPort {

    /**
     * Encrypts a plaintext PII value into an opaque, storable ciphertext string.
     *
     * @param plaintext the sensitive value (never null)
     * @return AES-256 ciphertext (base64/opaque), safe to persist
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a ciphertext produced by {@link #encrypt(String)} back to plaintext.
     *
     * @param ciphertext value previously returned by {@link #encrypt(String)}
     * @return the original plaintext
     */
    String decrypt(String ciphertext);
}
