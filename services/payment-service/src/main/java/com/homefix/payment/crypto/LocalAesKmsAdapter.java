package com.homefix.payment.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link KmsEncryptionPort} implementation performing envelope-style AES-256-GCM
 * encryption with a locally configured data key.
 *
 * <p>This adapter is intended for local development and tests. In production an AWS-KMS-backed
 * adapter can replace it behind the same port. Data-key material never leaves this process and the
 * ciphertext token embeds a random 96-bit IV so identical plaintexts encrypt differently,
 * satisfying the "not readable without the key" requirement (Requirement 12.9).
 *
 * <p>Token format: {@code v1:base64(iv || ciphertext+tag)}.
 */
@Component
@ConditionalOnProperty(name = "homefix.kms.provider", havingValue = "local", matchIfMissing = true)
public class LocalAesKmsAdapter implements KmsEncryptionPort {

    private static final String TOKEN_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public LocalAesKmsAdapter(@Value("${homefix.kms.data-key}") String base64DataKey) {
        byte[] keyBytes = Base64.getDecoder().decode(base64DataKey);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "homefix.kms.data-key must decode to a 128, 192, or 256-bit AES key");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return TOKEN_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new EncryptionException("Failed to encrypt value", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (!ciphertext.startsWith(TOKEN_PREFIX)) {
            throw new EncryptionException("Unrecognised ciphertext token format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext.substring(TOKEN_PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] payload = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, payload, 0, payload.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(payload), StandardCharsets.UTF_8);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("Failed to decrypt value", e);
        }
    }
}
