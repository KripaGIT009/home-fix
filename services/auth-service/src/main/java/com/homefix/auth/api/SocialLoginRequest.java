package com.homefix.auth.api;

import com.homefix.auth.social.SocialProvider;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /auth/login/social} (Requirement 1.5).
 *
 * @param provider      the social provider (GOOGLE or APPLE)
 * @param identityToken the raw provider-issued identity/ID token to validate
 */
public record SocialLoginRequest(
        @NotNull SocialProvider provider,
        @NotBlank String identityToken) {
}
