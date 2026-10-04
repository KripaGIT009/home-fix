package com.homefix.auth.api;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /auth/login/password}.
 *
 * <p>The bounds here are anti-abuse, not a password policy: they stop an unbounded body
 * reaching bcrypt. Strength rules belong wherever credentials are issued, not at sign-in,
 * because tightening them later must not lock out accounts that already exist.
 *
 * @param identifier an email address or a console username, case-insensitive. The field was
 *                   called {@code username} before email sign-in existed, and that name is still
 *                   accepted (email-auth design).
 * @param password   the raw password, verified against the stored bcrypt hash
 */
public record PasswordLoginRequest(

        @JsonAlias("username")
        @NotBlank(message = "identifier is required")
        @Size(max = 254, message = "identifier must be at most 254 characters")
        String identifier,

        @NotBlank(message = "password is required")
        @Size(max = 128, message = "password must be at most 128 characters")
        String password) {
}
