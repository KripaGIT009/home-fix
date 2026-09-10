package com.homefix.auth.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /auth/logout} (Requirement 1.12).
 */
public record LogoutRequest(@NotBlank String refreshToken) {
}
