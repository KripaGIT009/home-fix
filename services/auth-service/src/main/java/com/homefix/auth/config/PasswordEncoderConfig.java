package com.homefix.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing configuration (Requirement 26.5).
 *
 * <p>Exposes a bcrypt {@link PasswordEncoder} with a cost factor of 12 (the minimum permitted).
 * Any flow that stores a password (e.g. future email/password credentials, Admin accounts)
 * uses this bean, guaranteeing all stored passwords are bcrypt-hashed at cost &ge; 12. The cost
 * factor is asserted at construction so it can never be misconfigured below the minimum.
 */
@Configuration
public class PasswordEncoderConfig {

    /** Minimum bcrypt cost factor mandated by Requirement 26.5. */
    public static final int MIN_BCRYPT_COST = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(MIN_BCRYPT_COST);
    }
}
