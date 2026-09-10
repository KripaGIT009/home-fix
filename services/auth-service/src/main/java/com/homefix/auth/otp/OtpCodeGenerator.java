package com.homefix.auth.otp;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * Generates numeric OTP codes and computes salted hashes of them.
 *
 * <p>The raw OTP is never persisted; only its hash is stored (in Redis) so that a Redis
 * compromise does not directly leak live codes. Verification re-hashes the submitted code
 * and compares in constant time.
 */
@Component
public class OtpCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    /**
     * Generates a zero-padded numeric OTP of the requested length.
     */
    public String generate(int length) {
        if (length < 1 || length > 10) {
            throw new IllegalArgumentException("OTP length must be between 1 and 10");
        }
        int bound = (int) Math.pow(10, length);
        int value = random.nextInt(bound);
        return String.format("%0" + length + "d", value);
    }

    /**
     * Computes a SHA-256 hash of {@code code} salted with the mobile number so identical
     * codes for different phones hash differently.
     */
    public String hash(String code, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hashed = digest.digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /**
     * Constant-time comparison of two hashes to avoid timing side channels.
     */
    public boolean matches(String candidateCode, String salt, String expectedHash) {
        String candidateHash = hash(candidateCode, salt);
        return MessageDigest.isEqual(
                candidateHash.getBytes(StandardCharsets.UTF_8),
                expectedHash.getBytes(StandardCharsets.UTF_8));
    }
}
