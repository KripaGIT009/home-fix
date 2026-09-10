package com.homefix.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies the password encoder is bcrypt with a cost factor of at least 12 (Requirement 26.5).
 */
class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void encodesWithBcryptAtCostAtLeastTwelve() {
        String hash = encoder.encode("s3cr3t-password");

        // bcrypt hashes look like $2a$<cost>$... — assert the encoded cost is >= 12.
        assertThat(hash).startsWith("$2");
        int cost = Integer.parseInt(hash.split("\\$")[2]);
        assertThat(cost).isGreaterThanOrEqualTo(12);
    }

    @Test
    void matchesEncodedPassword() {
        String raw = "another-password";
        String hash = encoder.encode(raw);

        assertThat(encoder.matches(raw, hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    void minBcryptCostConstantMeetsRequirement() {
        assertThat(PasswordEncoderConfig.MIN_BCRYPT_COST).isGreaterThanOrEqualTo(12);
    }
}
