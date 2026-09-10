package com.homefix.customer.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.homefix.customer.config.CustomerCryptoProperties;

/**
 * Default {@link KmsEncryptionPort} adapter for local/dev/test environments.
 *
 * <p>Performs real AES-256-GCM encryption using a locally-configured 256-bit data key
 * (base64 in {@code homefix.kms.local-data-key}). This mirrors the AES-256 envelope
 * encryption a production AWS KMS adapter would provide, so PII is genuinely unreadable
 * without the key (Requirement 2.7). In production the {@code aws} provider selects a
 * KMS-backed adapter instead.
 *
 * <p>Activated when {@code homefix.kms.provider=local} (the default) or when no other
 * {@link KmsEncryptionPort} bean is present.
 */
@Component
@ConditionalOnProperty(prefix = "homefix.kms", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalAesKmsAdapter implements KmsEncryptionPort {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public LocalAesKmsAdapter(CustomerCryptoProperties properties) {
        byte[] keyBytes = Base64.getDecoder().decode(properties.getLocalDataKey());
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "homefix.kms.local-data-key must decode to 32 bytes (AES-256); got " + keyBytes.length);
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // Prepend IV so decryption is self-contained.
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext must not be null");
        }
        try {
            byte[] in = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(in, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PII decryption failed", e);
        }
    }
}
