package com.homefix.payment.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing and constant-time verification used by gateway adapters to authenticate
 * asynchronous callbacks (Requirement 12.5). Both Razorpay and Stripe document HMAC-SHA256 over
 * the raw webhook payload with a shared secret, so this helper is reused by both adapters and is
 * independently unit-testable.
 */
public final class HmacSignatures {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private HmacSignatures() {
    }

    /** Computes the lowercase hex HMAC-SHA256 of {@code payload} using {@code secret}. */
    public static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(raw);
        } catch (Exception e) {
            // A misconfigured signing key is a programming/config error, not a per-request failure.
            throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * Constant-time comparison of the expected signature for {@code payload} against a
     * provided {@code signature}. Returns {@code false} for any null or malformed input rather
     * than throwing, so callers can treat verification failure uniformly.
     */
    public static boolean verify(String secret, String payload, String signature) {
        if (secret == null || payload == null || signature == null) {
            return false;
        }
        String expected = hmacSha256Hex(secret, payload);
        // MessageDigest.isEqual is constant-time for equal-length arrays, mitigating timing attacks.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(hex[(b >> 4) & 0xF]).append(hex[b & 0xF]);
        }
        return sb.toString();
    }
}
