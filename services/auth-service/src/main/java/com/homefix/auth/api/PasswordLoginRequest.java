package com.homefix.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/login/password}.
 *
 * <p>The bounds here are anti-abuse, not a password policy: they stop an unbounded body
 * reaching bcrypt. Strength rules belong wherever credentials are issued, not at sign-in,
 * because tightening them later must not lock out accounts that already exist.
 *
 * @param username console username, case-insensitive
 * @param password the raw password, verified against the stored bcrypt hash
 */
public record PasswordLoginRequest(

        @NotBlank(message = "username is required")
        @Size(max = 64, message = "username must be at most 64 characters")
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 128, message = "password must be at most 128 characters")
        String password) {
}
