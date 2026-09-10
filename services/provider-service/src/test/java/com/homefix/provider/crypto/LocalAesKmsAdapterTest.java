package com.homefix.provider.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the AES-256-GCM KMS adapter (Requirement 4.9): ciphertext is not readable as
 * plaintext, round-trips correctly, and is non-deterministic (fresh IV per call).
 */
class LocalAesKmsAdapterTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final LocalAesKmsAdapter adapter = new LocalAesKmsAdapter(KEY);

    @Test
    void encryptThenDecrypt_roundTrips() {
        String plaintext = "ACCT-9988-7766";
        String cipher = adapter.encrypt(plaintext);
        assertThat(cipher).isNotEqualTo(plaintext).startsWith("v1:");
        assertThat(adapter.decrypt(cipher)).isEqualTo(plaintext);
    }

    @Test
    void encryptIsNonDeterministic() {
        String c1 = adapter.encrypt("same-value");
        String c2 = adapter.encrypt("same-value");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(adapter.decrypt(c1)).isEqualTo("same-value");
        assertThat(adapter.decrypt(c2)).isEqualTo("same-value");
    }

    @Test
    void nullValuesPassThrough() {
        assertThat(adapter.encrypt(null)).isNull();
        assertThat(adapter.decrypt(null)).isNull();
    }

    @Test
    void malformedCiphertext_isRejected() {
        assertThatThrownBy(() -> adapter.decrypt("not-a-valid-token"))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    void invalidKeyLength_isRejected() {
        assertThatThrownBy(() -> new LocalAesKmsAdapter("c2hvcnQ=")) // "short" — 5 bytes
                .isInstanceOf(IllegalArgumentException.class);
    }
}
