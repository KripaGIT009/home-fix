package com.homefix.customer.support;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import com.homefix.customer.crypto.KmsEncryptionPort;

/**
 * Deterministic in-memory {@link KmsEncryptionPort} for tests. Produces a reversible,
 * clearly-tagged ciphertext so tests can assert (a) that stored values differ from the
 * plaintext (encryption happened) and (b) that decryption round-trips.
 */
public class FakeKmsEncryptionPort implements KmsEncryptionPort {

    private static final String PREFIX = "enc::";

    @Override
    public String encrypt(String plaintext) {
        String b64 = Base64.getEncoder()
                .encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        return PREFIX + b64;
    }

    @Override
    public String decrypt(String ciphertext) {
        String b64 = ciphertext.substring(PREFIX.length());
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }
}
